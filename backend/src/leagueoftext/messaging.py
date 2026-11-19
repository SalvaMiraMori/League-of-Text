"""Notificacions push per *topic* de Firebase Cloud Messaging.

Cada partit és un *topic* (l'id del partit). L'app s'hi subscriu quan l'usuari
segueix el partit. S'envien missatges de dades perquè l'app decideixi com
mostrar-los (i pugui obrir directament el partit en tocar la notificació).
"""
from __future__ import annotations

import logging
import time

from firebase_admin import messaging

from . import store

log = logging.getLogger(__name__)

MAX_ATTEMPTS = 3


def send(match_id: str, title: str, body: str, *, game_id: str | None = None, event_type: str = "match") -> bool:
    store.ensure_app()
    data = {"matchId": match_id, "title": title, "text": body, "type": event_type}
    if game_id:
        data["gameId"] = game_id
    message = messaging.Message(
        topic=match_id,
        data=data,
        android=messaging.AndroidConfig(priority="high", ttl=600),
    )
    for attempt in range(1, MAX_ATTEMPTS + 1):
        try:
            message_id = messaging.send(message)
            log.info("Notificació enviada (%s): %s", message_id, title)
            return True
        except Exception as exc:  # noqa: BLE001 - firebase-admin llança diversos tipus
            log.warning("Error enviant la notificació (intent %d/%d): %s", attempt, MAX_ATTEMPTS, exc)
            time.sleep(attempt)
    return False


def send_game_events(events, match_id: str, game_id: str) -> None:
    """Envia tots els esdeveniments d'una execució en una sola crida (``send_each``)."""
    if not events:
        return
    store.ensure_app()
    messages = [
        messaging.Message(
            topic=match_id,
            data={
                "matchId": match_id,
                "gameId": game_id,
                "title": event.push_title(),
                "text": event.push_body(),
                "type": event.type,
            },
            android=messaging.AndroidConfig(priority="high", ttl=600),
        )
        for event in events
    ]
    for chunk_start in range(0, len(messages), 500):
        chunk = messages[chunk_start:chunk_start + 500]
        try:
            response = messaging.send_each(chunk)
        except Exception as exc:  # noqa: BLE001
            log.warning("send_each ha fallat (%s); s'envien d'un en un", exc)
            for message in chunk:
                send(match_id, message.data["title"], message.data["text"], game_id=game_id, event_type=message.data["type"])
            continue
        if response.failure_count:
            log.warning("%d notificacions no s'han pogut enviar", response.failure_count)
