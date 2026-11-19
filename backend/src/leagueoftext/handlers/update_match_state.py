"""UpdateMatchState: avança l'estat del partit.

* Sense ``gameId``: el partit acaba de començar. Envia l'avís d'inici (si és més d'un joc)
  i programa el primer joc a ``GameIsLive``.
* Amb ``gameId``: un joc ha acabat. Hi suma la victòria; si algú ja ha guanyat la sèrie,
  marca el partit com a ``completed``; si no, envia el marcador i programa el joc següent.
"""
from __future__ import annotations

import logging

from .. import messaging, store
from ..scheduling import GAME_IS_LIVE, Scheduler

log = logging.getLogger()
log.setLevel(logging.INFO)


def lambda_handler(event, context):
    match_id = str(event["matchId"])
    finished_game_id = event.get("gameId")
    scheduler = Scheduler.from_context(context)

    match = store.load_match(match_id)
    if match is None:
        raise RuntimeError(f"El partit {match_id} no té estat desat")

    if finished_game_id:
        game = store.load_game(match_id, finished_game_id)
        if game is None or not game.winner:
            raise RuntimeError(f"El joc {finished_game_id} no té guanyador")
        if not match.record_game_winner(game.winner, finished_game_id):
            log.info("El joc %s ja estava comptat (invocació duplicada)", finished_game_id)
            return {"matchId": match_id}
    elif match.games_played == 0 and match.best_of > 1:
        messaging.send(match_id, match.title(), "The match has just started!")

    if match.is_finished:
        store.save_match(match, completed=True)
        if match.best_of > 1:
            messaging.send(match_id, match.title(), match.winner_text())
        log.info("Partit %s acabat: %s", match_id, match.score_text())
        return {"matchId": match_id, "completed": True}

    if finished_game_id:
        messaging.send(match_id, match.title(), match.score_text())

    store.save_match(match)
    next_game = match.next_game_id()
    if next_game is None:
        # L'API no ha donat més jocs tot i que la sèrie no ha acabat: es tanca igualment.
        store.save_match(match, completed=True)
        return {"matchId": match_id, "completed": True}

    scheduler.add_polling_target(
        GAME_IS_LIVE,
        f"{GAME_IS_LIVE}-{next_game}",
        {"matchId": match_id, "gameId": next_game, "gameNumber": match.next_game_number()},
    )
    log.info("Joc %s programat (%s)", next_game, match.score_text())
    return {"matchId": match_id, "nextGame": next_game}
