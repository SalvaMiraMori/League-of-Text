"""UpdateGameState: llegeix les instantànies d'un joc en curs (cada minut, 10 s per finestra).

A cada execució processa unes quantes finestres de ``livestats`` a partir de ``date``,
genera els esdeveniments, els desa i els envia, i guarda la nova ``date`` a l'objectiu
de la regla. Quan el joc acaba i se'n sap el guanyador, treu l'objectiu i invoca
``UpdateMatchState``.
"""
from __future__ import annotations

import logging
from datetime import timedelta

from .. import lolesports, messaging, store
from ..scheduling import UPDATE_GAME_STATE, UPDATE_MATCH_STATE, Scheduler, utcnow

log = logging.getLogger()
log.setLevel(logging.INFO)

WINDOWS_PER_RUN = 6  # 6 × 10 s = el minut que passa entre execucions
MAX_WINDOWS_PER_RUN = 15  # per recuperar el retard si l'execució anterior va anar endarrerida
CATCH_UP_LAG = timedelta(minutes=2)
WINNER_ATTEMPTS_BEFORE_GUESS = 5


def lambda_handler(event, context):
    match_id = str(event["matchId"])
    game_id = str(event["gameId"])
    date = lolesports.parse_timestamp(event["date"])
    scheduler = Scheduler.from_context(context)
    target_id = f"{UPDATE_GAME_STATE}-{game_id}"

    game = store.load_game(match_id, game_id)
    if game is None:
        raise RuntimeError(f"El joc {game_id} no té estat desat")

    windows = 0
    while not game.ended and windows < MAX_WINDOWS_PER_RUN:
        if windows >= WINDOWS_PER_RUN and date > utcnow() - CATCH_UP_LAG:
            break
        window = lolesports.get_game_window(game_id, date)
        if window is None:
            # Encara no hi ha dades per a aquesta finestra: es torna a provar al minut següent.
            break
        for frame in window["frames"]:
            game.update(frame)
            if game.ended:
                break
        date += timedelta(seconds=10)
        windows += 1

    if game.ended and not game.winner:
        match = store.load_match(match_id)
        winner = lolesports.find_game_winner(match_id, game_id, match.wins() if match else {})
        game.winner_attempts += 1
        if winner is None and game.winner_attempts >= WINNER_ATTEMPTS_BEFORE_GUESS:
            winner = game.guess_winner()
            log.warning("L'API no indica el guanyador de %s; s'estima %s", game_id, winner)
        if winner:
            game.finish(winner)

    events = game.drain_events()
    store.save_game(game, events)
    messaging.send_game_events(events, match_id, game_id)

    if game.winner:
        scheduler.remove_polling_target(UPDATE_GAME_STATE, target_id)
        scheduler.invoke_async(UPDATE_MATCH_STATE, {"matchId": match_id, "gameId": game_id})
        log.info("Joc %s acabat; guanyador %s", game_id, game.winner)
    else:
        scheduler.add_polling_target(
            UPDATE_GAME_STATE,
            target_id,
            {"matchId": match_id, "gameId": game_id, "date": lolesports.format_window_time(date)},
        )
    return {"gameId": game_id, "windows": windows, "events": len(events), "winner": game.winner}
