"""Importació de partits ja jugats a partir de l'estat final de GRID (Series State).

Construeix els mateixos documents que el backend en directe (``Matches/{id}``, ``Games/{id}`` i
les notificacions), però sense enviar res per FCM. Ho fa servir ``scripts/import_grid_series.py``.

Com que GRID només dona l'estat final de cada joc (no la seqüència d'esdeveniments), la
cronologia de cada joc té només l'inici i el final; les estadístiques del joc (kills, or,
torres, dracs, barons, inhibidors) són les finals.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Iterable, Optional

from . import grid
from .game import BLUE, RED, SIDES, Game, GameEvent, TeamStats
from .lolesports import parse_timestamp
from .match import Match


@dataclass
class ImportedGame:
    number: int
    game: Game
    events: list[GameEvent]


@dataclass
class ImportedMatch:
    event: dict
    match: Match
    games: list[ImportedGame] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)

    @property
    def match_id(self) -> str:
        return self.match.match_id


def _winner_id(game_state: dict) -> Optional[str]:
    winner = next((t for t in game_state.get("teams") or [] if t.get("won")), None)
    return str(winner["id"]) if winner else None


def build_completed_game(match: Match, state: dict, game_state: dict) -> tuple[Optional[ImportedGame], Optional[str]]:
    """Joc acabat → (``ImportedGame``, ``None``) o (``None``, avís) si no se'n pot saber el guanyador."""
    number = int(game_state.get("sequenceNumber", 0))
    game_id = grid.game_id_for(match.match_id, number)
    winner = _winner_id(game_state)
    if winner is None:
        return None, f"el joc {number} no té guanyador a GRID"

    window = grid.game_to_window(state, game_state)
    game_number = 0 if match.best_of == 1 else number
    game = Game.start(match.match_id, game_id, window, game_number, match.team_codes())
    game.drain_events()  # els esdeveniments de "inici tardà" tindrien totes l'hora del final
    game.seq = 0

    number_text = f"game {game_number} " if game_number > 0 else ""
    game.playing_seconds = 0
    game._emit("game_start", f"{game.teams[BLUE].name} vs {game.teams[RED].name} {number_text}has just started!")
    game.pending[-1].stats = {side: TeamStats().to_dict() for side in SIDES}  # a l'inici, tot a zero

    game.playing_seconds = float(((game_state.get("clock") or {}).get("currentSeconds")) or 0)
    game.finish(winner)
    return ImportedGame(number, game, game.drain_events()), None


def build_completed_match(series: dict, state: dict | None) -> Optional[ImportedMatch]:
    """Sèrie de Central Data + estat final de Series State → partit acabat.

    Retorna ``None`` si la sèrie encara no ha acabat (o GRID no en té estat).
    """
    if not state or not state.get("finished"):
        return None
    event = grid.series_to_event(series, "completed")
    imported = ImportedMatch(event=event, match=Match.from_event(event))
    match = imported.match

    games = sorted(
        (g for g in state.get("games") or [] if g.get("finished")),
        key=lambda g: int(g.get("sequenceNumber", 0)),
    )
    for game_state in games:
        result, warning = build_completed_game(match, state, game_state)
        if warning:
            imported.warnings.append(warning)
            continue
        try:
            match.record_game_winner(result.game.winner, result.game.game_id)
        except ValueError as exc:
            imported.warnings.append(str(exc))
            continue
        imported.games.append(result)

    expected = {str(t["id"]): int(t.get("score", 0)) for t in state.get("teams") or []}
    if expected and expected != match.wins():
        imported.warnings.append(f"el marcador de GRID ({expected}) no coincideix amb els jocs ({match.wins()})")
    series_winner = next((str(t["id"]) for t in state.get("teams") or [] if t.get("won")), None)
    if series_winner and match.winner != series_winner:
        imported.warnings.append(f"GRID diu que ha guanyat {series_winner}; pels jocs, {match.winner}")
        match.winner = series_winner
    return imported


def summary(imported: ImportedMatch) -> str:
    start = imported.event["startTime"][:16].replace("T", " ")
    phase = imported.event.get("blockName") or ""
    return f"{imported.match_id:>9}  {start}  {phase:<10} {imported.match.score_text()}"


# --------------------------------------------------------------------------- duplicats


def _day(value: str) -> str:
    return parse_timestamp(value).date().isoformat() if value else ""


def _codes(doc: dict) -> frozenset[str]:
    return frozenset((t.get("code") or t.get("name") or "").upper() for t in doc.get("teams") or [])


def find_duplicates(imported: Iterable[ImportedMatch], existing: Iterable[tuple[str, dict]]) -> list[tuple[str, str]]:
    """Partits que ja hi ha a Firestore amb un altre id (p. ex. de l'API de LoL Esports) i que
    són el mateix enfrontament (mateix dia i mateixos equips). Retorna ``(id existent, id GRID)``."""
    by_key = {(_day(m.event["startTime"]), _codes(m.event["match"])): m.match_id for m in imported}
    grid_ids = set(by_key.values())
    duplicates = []
    for doc_id, doc in existing:
        if doc_id in grid_ids:
            continue
        key = (_day(doc.get("startTime", "")), _codes(doc))
        if key in by_key:
            duplicates.append((doc_id, by_key[key]))
    return duplicates


# --------------------------------------------------------------------------- Firestore


def save_completed_match(imported: ImportedMatch) -> None:
    """Desa el partit, els seus jocs i les notificacions. Es pot repetir sense duplicar res."""
    from firebase_admin import firestore

    from . import store

    match = imported.match
    doc = store.match_document_from_event(imported.event)
    batch = store.db().batch()
    batch.set(
        store.match_ref(match.match_id),
        {
            **doc,
            "state": store.COMPLETED,
            "score": match.wins(),
            "winnerId": match.winner,
            "engine": match.to_dict(),
            "importedFrom": "grid",
            "updatedAt": firestore.SERVER_TIMESTAMP,
        },
        merge=True,
    )
    for item in imported.games:
        batch.set(
            store.game_ref(match.match_id, item.game.game_id),
            {"esportsGameId": item.game.game_id, "number": item.number},
            merge=True,
        )
    batch.commit()
    for item in imported.games:
        store.save_game(item.game, item.events)


def existing_matches(league: str) -> list[tuple[str, dict]]:
    """Partits que ja hi ha a Firestore d'una lliga (per buscar duplicats)."""
    from . import store

    collection = store.db().collection(store.MATCHES)
    try:
        from google.cloud.firestore_v1.base_query import FieldFilter

        query = collection.where(filter=FieldFilter("league.name", "==", league))
    except ImportError:  # versions antigues de google-cloud-firestore
        query = collection.where("league.name", "==", league)
    return [(snap.id, snap.to_dict() or {}) for snap in query.stream()]

