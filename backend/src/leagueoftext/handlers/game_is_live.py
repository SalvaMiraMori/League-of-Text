"""GameIsLive: espera (cada minut) que el feed ``livestats`` del joc estigui disponible.

Quan ho està, crea el :class:`~leagueoftext.game.Game`, envia l'avís d'inici, passa el
joc a la regla ``UpdateGameState`` i treu el seu objectiu de ``GameIsLive``.
"""
from __future__ import annotations

import logging

from .. import lolesports, messaging, store
from ..game import Game
from ..scheduling import GAME_IS_LIVE, UPDATE_GAME_STATE, Scheduler

log = logging.getLogger()
log.setLevel(logging.INFO)


def lambda_handler(event, context):
    match_id = str(event["matchId"])
    game_id = str(event["gameId"])
    scheduler = Scheduler.from_context(context)
    target_id = f"{GAME_IS_LIVE}-{game_id}"

    if store.load_game(match_id, game_id) is not None:
        # Execució duplicada: el joc ja s'ha creat.
        scheduler.remove_polling_target(GAME_IS_LIVE, target_id)
        return {"gameId": game_id, "started": True}

    window = lolesports.get_game_window(game_id)
    if window is None or "gameMetadata" not in window:
        log.info("El joc %s encara no té dades en directe", game_id)
        return {"gameId": game_id, "started": False}

    match = store.load_match(match_id)
    game_number = int(event.get("gameNumber", match.next_game_number() if match else 0))
    game = Game.start(match_id, game_id, window, game_number, match.team_codes() if match else None)

    events = game.drain_events()
    store.save_game(game, events)
    messaging.send_game_events(events, match_id, game_id)

    next_window = lolesports.next_window_start(window["frames"][0]["rfc460Timestamp"])
    scheduler.add_polling_target(
        UPDATE_GAME_STATE,
        f"{UPDATE_GAME_STATE}-{game_id}",
        {"matchId": match_id, "gameId": game_id, "date": lolesports.format_window_time(next_window)},
    )
    scheduler.remove_polling_target(GAME_IS_LIVE, target_id)
    log.info("Joc %s començat", game_id)
    return {"gameId": game_id, "started": True}
