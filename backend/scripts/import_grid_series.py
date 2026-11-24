"""Importa a Firestore partits ja jugats a partir de l'API de GRID (estat final de cada sèrie).

Per defecte fa una **simulació**: mostra què importaria i no escriu res. Afegeix ``--apply``
per escriure. Es pot tornar a executar: els documents tenen els mateixos ids i es sobreescriuen.

Exemples (des de ``backend/``)::

    python scripts/import_grid_series.py                       # Worlds 2026, simulació
    python scripts/import_grid_series.py --apply               # escriu a Firestore
    python scripts/import_grid_series.py --series 3012986      # només una sèrie
    python scripts/import_grid_series.py --tournament "MSI - 2027" --league MSI --from 2027-06-01 --to 2027-07-31

Necessita:

* ``GRID_API_KEY`` (variable d'entorn o ``.env`` a l'arrel del repositori);
* credencials de Firebase: ``GOOGLE_APPLICATION_CREDENTIALS`` amb la ruta al JSON del compte de
  servei (només per a ``--apply`` i per buscar duplicats).
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from datetime import datetime, time as dtime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "backend" / "src"))

from leagueoftext import backfill, grid  # noqa: E402

SERIES_STATE_PAUSE_S = 0.4  # Open Access: 180 peticions/minut a Series State


def load_env() -> None:
    """Llegeix ``.env`` de l'arrel (sense sobreescriure variables ja definides)."""
    env_file = ROOT / ".env"
    if not env_file.exists():
        return
    for line in env_file.read_text(encoding="utf-8").splitlines():
        name, sep, value = line.strip().partition("=")
        if sep and name and not name.startswith("#"):
            os.environ.setdefault(name.strip(), value.strip().strip('"').strip("'"))


def parse_day(value: str, end: bool = False) -> datetime:
    day = datetime.strptime(value, "%Y-%m-%d").date()
    return datetime.combine(day, dtime.max if end else dtime.min, tzinfo=timezone.utc)


def select_series(args) -> list[dict]:
    if args.series:
        found = []
        for series_id in args.series:
            series = grid.graphql(grid.CENTRAL_DATA_URL, grid.SERIES_QUERY, {"id": series_id}).get("series")
            if series:
                found.append(series)
            else:
                print(f"  La sèrie {series_id} no existeix a GRID", file=sys.stderr)
        return found
    wanted = args.tournament.lower()
    selected = []
    for series in grid.iter_series(parse_day(args.date_from), parse_day(args.date_to, end=True)):
        tournament = series.get("tournament") or {}
        names = [tournament.get("name") or "", (tournament.get("parent") or {}).get("name") or ""]
        if any(wanted in name.lower() for name in names):
            selected.append(series)
    return selected


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Importa partits ja jugats des de GRID a Firestore")
    parser.add_argument("--tournament", default="Worlds - 2026", help="Text que ha de contenir el nom del torneig")
    parser.add_argument("--league", default=None, help="Nom de la lliga a l'app (per defecte, el que dedueix de LEAGUES)")
    parser.add_argument("--from", dest="date_from", default="2026-10-01")
    parser.add_argument("--to", dest="date_to", default="2026-11-30")
    parser.add_argument("--series", nargs="+", help="Ids de sèries concretes (en lloc de buscar per torneig)")
    parser.add_argument("--apply", action="store_true", help="Escriu a Firestore (si no, només simula)")
    parser.add_argument("--no-duplicates-check", action="store_true", help="No busca duplicats a Firestore")
    parser.add_argument("--delete-duplicates", action="store_true",
                        help="Esborra de Firestore els duplicats trobats (partits del mateix dia i equips amb un altre id)")
    parser.add_argument("--out", help="Desa també el resultat en un fitxer JSON")
    args = parser.parse_args(argv)

    load_env()
    if not os.environ.get("GRID_API_KEY"):
        print("Falta GRID_API_KEY (variable d'entorn o .env a l'arrel del repositori).", file=sys.stderr)
        return 1

    try:
        series_list = select_series(args)
    except grid.GridError as exc:
        print(f"Error de GRID: {exc}", file=sys.stderr)
        return 1
    print(f"{len(series_list)} sèries trobades\n")

    imported, skipped = [], []
    for index, series in enumerate(series_list):
        if index:
            time.sleep(SERIES_STATE_PAUSE_S)
        try:
            state = grid.get_series_state(series["id"])
        except grid.GridError as exc:
            skipped.append((series["id"], str(exc)))
            continue
        item = backfill.build_completed_match(series, state)
        if item is None:
            skipped.append((series["id"], "encara no ha acabat"))
            continue
        if args.league:
            item.event["league"].update(name=args.league, slug=grid.league_slug(args.league))
        imported.append(item)
        print(backfill.summary(item) + f"  ({len(item.games)} jocs)")
        for warning in item.warnings:
            print(f"           ⚠ {warning}")

    for series_id, reason in skipped:
        print(f"{series_id:>9}  omesa: {reason}")
    print(f"\n{len(imported)} partits per importar, {len(skipped)} omesos")

    if args.out:
        payload = [
            {
                "match": {**backfill_doc(item), "engine": item.match.to_dict()},
                "games": [{"number": g.number, **g.game.to_dict(), "events": [e.to_firestore() for e in g.events]}
                          for g in item.games],
            }
            for item in imported
        ]
        Path(args.out).write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")
        print(f"Resultat desat a {args.out}")

    duplicates = []
    if imported and not args.no_duplicates_check:
        leagues = {item.event["league"]["name"] for item in imported}
        try:
            existing = [doc for league in leagues for doc in backfill.existing_matches(league)]
        except Exception as exc:  # sense credencials de Firebase, p. ex.
            print(f"\nNo s'han pogut buscar duplicats a Firestore: {exc}")
            if args.apply:
                return 1
        else:
            duplicates = backfill.find_duplicates(imported, existing)
            if duplicates:
                print(f"\n{len(duplicates)} partits ja són a Firestore amb un altre id (mateix dia i equips):")
                for old_id, grid_id in duplicates:
                    print(f"  {old_id}  →  {grid_id}")
                if not args.delete_duplicates:
                    print("  Es veurien dues vegades a l'app. Afegeix --delete-duplicates per esborrar els antics.")

    if not args.apply:
        print("\nSimulació: no s'ha escrit res. Afegeix --apply per importar.")
        return 0

    from leagueoftext import store

    for item in imported:
        backfill.save_completed_match(item)
    print(f"\n{len(imported)} partits importats a Firestore")
    if args.delete_duplicates:
        for old_id, _ in duplicates:
            store.delete_match(old_id)
        print(f"{len(duplicates)} duplicats esborrats")
    return 0


def backfill_doc(item) -> dict:
    from leagueoftext import store

    return {**store.match_document_from_event(item.event), "state": "completed",
            "score": item.match.wins(), "winnerId": item.match.winner}


if __name__ == "__main__":
    sys.exit(main())
