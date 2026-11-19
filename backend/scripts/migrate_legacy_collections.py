"""Copia les dades de l'antic model (ScheduledMatches / LiveMatches / CompletedMatches) a ``Matches``.

Ús:
    export GOOGLE_APPLICATION_CREDENTIALS=/ruta/a/la/clau.json
    python scripts/migrate_legacy_collections.py            # simulació (no escriu res)
    python scripts/migrate_legacy_collections.py --apply    # escriu a Firestore

No esborra les col·leccions antigues. Les notificacions antigues es copien tal com són:
l'app sap llegir el format antic (camps blueTeamKills, redTeamGold...).
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from leagueoftext import store  # noqa: E402

LEGACY = {"ScheduledMatches": store.SCHEDULED, "LiveMatches": store.LIVE, "CompletedMatches": store.COMPLETED}


def migrate(apply: bool) -> None:
    db = store.db()
    for collection, state in LEGACY.items():
        for match in db.collection(collection).stream():
            data = match.to_dict()
            data.pop("matchObjectJson", None)
            data["state"] = state
            data.setdefault("bestOf", data.get("numberOfGames", 1))
            target = db.collection(store.MATCHES).document(match.id)
            print(f"{collection}/{match.id} -> {store.MATCHES}/{match.id} ({state})")
            if not apply:
                continue
            batch, count = db.batch(), 0
            batch.set(target, data, merge=True)
            for game in match.reference.collection("Games").stream():
                game_data = game.to_dict()
                game_data.pop("gameObjectJson", None)
                if state == store.COMPLETED:
                    game_data["state"] = "completed"
                game_target = target.collection(store.GAMES).document(game.id)
                batch.set(game_target, game_data, merge=True)
                for notification in game.reference.collection("Notifications").stream():
                    batch.set(game_target.collection(store.NOTIFICATIONS).document(notification.id), notification.to_dict())
                    count += 1
                    if count >= store.BATCH_LIMIT:
                        batch.commit()
                        batch, count = db.batch(), 0
            batch.commit()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--apply", action="store_true", help="escriu de debò (per defecte només mostra què faria)")
    migrate(parser.parse_args().apply)
