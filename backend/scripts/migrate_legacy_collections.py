"""Copia les dades de l'antic model (ScheduledMatches / LiveMatches / CompletedMatches) a ``Matches``.

Ús:
    export GOOGLE_APPLICATION_CREDENTIALS=/ruta/a/la/clau.json
    python scripts/migrate_legacy_collections.py            # simulació (no escriu res)
    python scripts/migrate_legacy_collections.py --apply    # escriu a Firestore
    python scripts/migrate_legacy_collections.py --apply --recent 0   # tots els partits acabats

No esborra les col·leccions antigues. A més de copiar els documents:

* recupera el marcador (``score``) i el guanyador (``winnerId``) de l'antic ``matchObjectJson``;
* recupera el guanyador i els equips de cada joc de l'antic ``gameObjectJson``;
* els partits que van quedar a ``LiveMatches`` fa més d'un dia es marquen com a acabats,
  i els de ``ScheduledMatches`` que ja han passat no es copien (no es van arribar a jugar
  amb el backend antic en marxa).

Per estalviar quota (el pla gratuït de Firestore permet 20.000 escriptures al dia), per defecte
només es copien els 60 partits acabats més recents, que és el que mostra l'app (``--recent 0``
per copiar-los tots). Si la quota s'esgota a mitges, es pot tornar a executar: els partits que
ja s'havien copiat sencers se salten (``--force`` per tornar-los a copiar).

Les notificacions es copien tal com són: l'app sap llegir el format antic.

El backend antic no desava la victòria de l'última partida dels partits acabats, així que el
marcador es reconstrueix (vegeu ``convert_match``). Per corregir partits ja migrats amb la
versió anterior d'aquest script, sense tornar a copiar-ho tot:

    python scripts/migrate_legacy_collections.py --scores-only            # simulació
    python scripts/migrate_legacy_collections.py --scores-only --apply
"""
from __future__ import annotations

import argparse
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

LEGACY = {"ScheduledMatches": "scheduled", "LiveMatches": "live", "CompletedMatches": "completed"}
STALE_LIVE_AFTER = timedelta(days=1)


def _parse_time(value) -> datetime | None:
    if isinstance(value, datetime):
        return value if value.tzinfo else value.replace(tzinfo=timezone.utc)
    if isinstance(value, str):
        try:
            return datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError:
            return None
    return None


def _team_names(data: dict, engine_teams: dict) -> dict[str, str]:
    """Nom/codi (en minúscules) -> id de l'equip, per identificar el guanyador antic, que es desava pel nom."""
    names: dict[str, str] = {}
    for team in data.get("teams") or []:
        if isinstance(team, dict) and team.get("id"):
            for key in ("name", "code"):
                if team.get(key):
                    names[str(team[key]).strip().lower()] = str(team["id"])
    for team_id, info in engine_teams.items():
        if isinstance(info, dict) and info.get("name"):
            names[str(info["name"]).strip().lower()] = str(team_id)
    return names


def _legacy_winner(engine: dict, data: dict, engine_teams: dict) -> str | None:
    """Id de l'equip guanyador segons l'antic ``matchObjectJson`` (``winner`` era un objecte amb el nom)."""
    winner = engine.get("winner")
    if not winner:
        return None
    if isinstance(winner, dict):
        for key in ("esportsTeamId", "id"):
            if winner.get(key) and str(winner[key]) in engine_teams:
                return str(winner[key])
        name = winner.get("name") or winner.get("code")
    else:
        if str(winner) in engine_teams:
            return str(winner)
        name = winner
    return _team_names(data, engine_teams).get(str(name).strip().lower()) if name else None


def convert_match(collection: str, data: dict, now: datetime, game_winners: list[str] | None = None) -> dict | None:
    """Converteix un document antic de partit. Retorna ``None`` si no s'ha de copiar.

    El backend antic tancava el partit sense desar la victòria de l'última partida, de manera
    que els ``wins`` de ``matchObjectJson`` dels partits acabats van una partida per sota
    (1-0 desat com 0-0, 2-1 com 1-1, 3-2 com 2-2...). El marcador es reconstrueix així:

    1. es parteix dels ``wins`` antics;
    2. si es coneixen els guanyadors de cada joc (``game_winners``), es compten;
    3. si el partit ha acabat i se sap qui l'ha guanyat, aquest equip té com a mínim les
       victòries necessàries.
    """
    state = LEGACY[collection]
    start = _parse_time(data.get("startTime"))
    if state == "scheduled" and start is not None and start < now:
        return None
    if state == "live" and start is not None and now - start > STALE_LIVE_AFTER:
        state = "completed"

    doc = {k: v for k, v in data.items() if k != "matchObjectJson"}
    doc["state"] = state
    doc.setdefault("bestOf", data.get("numberOfGames", 1))

    engine = data.get("matchObjectJson") or {}
    teams = engine.get("teams") or {}
    if isinstance(teams, dict) and teams:
        score = {str(team_id): int((info or {}).get("wins", 0)) for team_id, info in teams.items()}
        needed = int(engine.get("necessaryWins") or (int(doc["bestOf"]) // 2 + 1))

        for team_id in game_winners or []:
            if str(team_id) in score:
                counted = sum(1 for w in game_winners if str(w) == str(team_id))
                score[str(team_id)] = max(score[str(team_id)], counted)

        # Un partit encara en directe no té guanyador definitiu.
        winner = _legacy_winner(engine, data, teams) if state == "completed" else None
        if winner and score.get(winner, 0) < needed:
            score[winner] = needed

        doc["score"] = score
        winners = [team_id for team_id, wins in score.items() if wins >= needed]
        if winners:
            doc["winnerId"] = winners[0]
        elif winner:
            doc["winnerId"] = winner
        elif state == "completed" and score and len(set(score.values())) > 1:
            doc["winnerId"] = max(score, key=score.get)
    return doc


def convert_game(data: dict, match_state: str) -> dict:
    doc = {k: v for k, v in data.items() if k != "gameObjectJson"}
    engine = data.get("gameObjectJson") or {}
    if engine.get("winner"):
        doc["winnerId"] = str(engine["winner"])
        doc["state"] = "completed"
    elif match_state == "completed":
        doc["state"] = "completed"
    teams = engine.get("teams") or {}
    for side in ("blue", "red"):
        team = teams.get(f"{side}Team") or {}
        if team.get("name"):
            doc[f"{side}Team"] = team["name"]
        if team.get("esportsTeamId"):
            doc[f"{side}TeamId"] = str(team["esportsTeamId"])
    return doc


def migrate(apply: bool, recent: int, force: bool, scores_only: bool = False) -> None:
    from google.api_core.exceptions import ResourceExhausted, RetryError
    from google.cloud.firestore import Query

    from leagueoftext import store

    db = store.db()
    now = datetime.now(timezone.utc)
    copied = skipped = already = writes = 0
    try:
        for collection in LEGACY:
            query = db.collection(collection)
            if collection == "CompletedMatches" and recent > 0:
                # L'app només mostra els 60 resultats més recents: no cal gastar quota amb la resta.
                query = query.order_by("startTime", direction=Query.DESCENDING).limit(recent)
            for match in query.stream():
                data = match.to_dict()
                base = convert_match(collection, data, now)
                if base is None:
                    skipped += 1
                    continue
                target = db.collection(store.MATCHES).document(match.id)
                if apply and not force and not scores_only:
                    existing = target.get()
                    if existing.exists and existing.get("migratedAt") is not None:
                        already += 1
                        continue
                games = list(match.reference.collection("Games").stream())
                converted_games = [(game, convert_game(game.to_dict(), base["state"])) for game in games]
                game_winners = [g["winnerId"] for _, g in converted_games if g.get("winnerId")]
                doc = convert_match(collection, data, now, game_winners)
                copied += 1
                print(f"{collection}/{match.id} -> {store.MATCHES}/{match.id} ({doc['state']}, {doc.get('score', '-')})")
                if not apply:
                    continue
                if scores_only:
                    # Només es corregeix el marcador dels partits que ja s'havien migrat.
                    if target.get().exists:
                        fix = {"score": doc.get("score", {})}
                        if doc.get("winnerId"):
                            fix["winnerId"] = doc["winnerId"]
                        target.set(fix, merge=True)
                        writes += 1
                    continue
                batch, count = db.batch(), 1
                batch.set(target, doc, merge=True)
                for game, game_doc in converted_games:
                    game_target = target.collection(store.GAMES).document(game.id)
                    batch.set(game_target, game_doc, merge=True)
                    count += 1
                    for notification in game.reference.collection("Notifications").stream():
                        batch.set(game_target.collection(store.NOTIFICATIONS).document(notification.id), notification.to_dict())
                        count += 1
                        if count >= store.BATCH_LIMIT:
                            batch.commit()
                            writes += count
                            batch, count = db.batch(), 0
                # La marca es posa al final: si s'atura a mitges, aquest partit es tornarà a copiar.
                batch.set(target, {"migratedAt": now}, merge=True)
                batch.commit()
                writes += count + 1
    except (ResourceExhausted, RetryError) as exc:
        if isinstance(exc, RetryError) and not isinstance(exc.cause, ResourceExhausted):
            raise
        print(
            "\nS'ha esgotat la quota diària de Firestore (pla gratuït: 20.000 escriptures i 50.000 lectures).\n"
            "Es reinicia a mitjanit, hora del Pacífic (les 9:00 a Espanya). Torna a executar la mateixa ordre\n"
            "llavors: els partits ja migrats se saltaran."
        )
        print(f"Escriptures fetes en aquesta execució: ~{writes}.")
        sys.exit(1)

    action = "copiats" if apply else "es copiarien"
    print(f"\nPartits {action}: {copied}. Ja migrats abans: {already}. Descartats (ja passats): {skipped}.")
    if apply:
        print(f"Escriptures: ~{writes}.")
    else:
        print("Simulació: torna-ho a executar amb --apply per escriure.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--apply", action="store_true", help="escriu de debò (per defecte només mostra què faria)")
    parser.add_argument("--recent", type=int, default=60,
                        help="quants partits acabats (els més recents) es copien; 0 = tots (per defecte, 60)")
    parser.add_argument("--force", action="store_true", help="torna a copiar també els partits ja migrats")
    parser.add_argument("--scores-only", action="store_true",
                        help="només corregeix score i winnerId dels partits ja migrats (1 escriptura per partit)")
    args = parser.parse_args()
    migrate(args.apply, args.recent, args.force, args.scores_only)
