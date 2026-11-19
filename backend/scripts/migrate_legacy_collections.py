"""Copia les dades de l'antic model (ScheduledMatches / LiveMatches / CompletedMatches) a ``Matches``.

Ús:
    export GOOGLE_APPLICATION_CREDENTIALS=/ruta/a/la/clau.json
    python scripts/migrate_legacy_collections.py            # simulació (no escriu res)
    python scripts/migrate_legacy_collections.py --apply    # escriu a Firestore

No esborra les col·leccions antigues. A més de copiar els documents:

* recupera el marcador (``score``) i el guanyador (``winnerId``) de l'antic ``matchObjectJson``;
* recupera el guanyador i els equips de cada joc de l'antic ``gameObjectJson``;
* els partits que van quedar a ``LiveMatches`` fa més d'un dia es marquen com a acabats,
  i els de ``ScheduledMatches`` que ja han passat no es copien (no es van arribar a jugar
  amb el backend antic en marxa).

Les notificacions es copien tal com són: l'app sap llegir el format antic.
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


def convert_match(collection: str, data: dict, now: datetime) -> dict | None:
    """Converteix un document antic de partit. Retorna ``None`` si no s'ha de copiar."""
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
        doc["score"] = score
        needed = engine.get("necessaryWins") or (int(doc["bestOf"]) // 2 + 1)
        winners = [team_id for team_id, wins in score.items() if wins >= needed]
        if winners:
            doc["winnerId"] = winners[0]
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


def migrate(apply: bool) -> None:
    from leagueoftext import store

    db = store.db()
    now = datetime.now(timezone.utc)
    copied = skipped = 0
    for collection in LEGACY:
        for match in db.collection(collection).stream():
            doc = convert_match(collection, match.to_dict(), now)
            if doc is None:
                skipped += 1
                print(f"  {collection}/{match.id}: ja ha passat, no es copia")
                continue
            copied += 1
            print(f"{collection}/{match.id} -> {store.MATCHES}/{match.id} ({doc['state']}, {doc.get('score', '-')})")
            if not apply:
                continue
            target = db.collection(store.MATCHES).document(match.id)
            batch, count = db.batch(), 1
            batch.set(target, doc, merge=True)
            for game in match.reference.collection("Games").stream():
                game_target = target.collection(store.GAMES).document(game.id)
                batch.set(game_target, convert_game(game.to_dict(), doc["state"]), merge=True)
                count += 1
                for notification in game.reference.collection("Notifications").stream():
                    batch.set(game_target.collection(store.NOTIFICATIONS).document(notification.id), notification.to_dict())
                    count += 1
                    if count >= store.BATCH_LIMIT:
                        batch.commit()
                        batch, count = db.batch(), 0
            batch.commit()
    action = "copiats" if apply else "es copiarien"
    print(f"\nPartits {action}: {copied}. Descartats: {skipped}.")
    if not apply:
        print("Simulació: torna-ho a executar amb --apply per escriure.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--apply", action="store_true", help="escriu de debò (per defecte només mostra què faria)")
    migrate(parser.parse_args().apply)
