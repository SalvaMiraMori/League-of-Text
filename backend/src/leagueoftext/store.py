"""Accés a Firestore.

Model de dades (una sola col·lecció amb un camp ``state``, en lloc de moure documents
entre ``ScheduledMatches``, ``LiveMatches`` i ``CompletedMatches``)::

    Matches/{matchId}
        state: scheduled | live | completed
        startTime, bestOf, league{...}, blockName, teams[...], score{teamId: wins}, winnerId
        engine: estat intern del backend (Match.to_dict)
        Games/{gameId}
            number, state: unstarted | inProgress | completed, winnerId
            blueTeamId, redTeamId, playingSeconds, paused, stats{blue{...}, red{...}}
            engine: estat intern del backend (Game.to_dict)
            Notifications/{seq}: type, text, playingTime, side, stats{...}

    Users/{uid}: subscribedMatches[...]   (l'escriu l'app)
"""
from __future__ import annotations

import logging
from functools import lru_cache
from typing import Iterable, Optional

import firebase_admin
from firebase_admin import credentials, firestore

from . import config
from .game import Game, GameEvent
from .match import Match, team_from_api

log = logging.getLogger(__name__)

MATCHES = "Matches"
GAMES = "Games"
NOTIFICATIONS = "Notifications"

SCHEDULED = "scheduled"
LIVE = "live"
COMPLETED = "completed"

BATCH_LIMIT = 450  # Firestore permet 500 operacions per lot


def ensure_app() -> None:
    if firebase_admin._apps:
        return
    info = config.firebase_credentials_info()
    cred = credentials.Certificate(info) if info else credentials.ApplicationDefault()
    firebase_admin.initialize_app(credential=cred)


@lru_cache(maxsize=1)
def db():
    ensure_app()
    return firestore.client()


def match_ref(match_id: str):
    return db().collection(MATCHES).document(match_id)


def game_ref(match_id: str, game_id: str):
    return match_ref(match_id).collection(GAMES).document(game_id)


# --------------------------------------------------------------------------- partits


def get_match(match_id: str) -> Optional[dict]:
    snapshot = match_ref(match_id).get()
    return snapshot.to_dict() if snapshot.exists else None


def match_document_from_event(event: dict) -> dict:
    """Camps visibles per l'app a partir d'un esdeveniment de l'API."""
    details = event.get("match") or {}
    league = event.get("league") or {}
    teams = [team_from_api(t).to_dict() for t in (details.get("teams") or event.get("teams") or [])]
    for team in teams:
        team.pop("wins", None)
    best_of = int((details.get("strategy") or {}).get("count") or 1)
    return {
        "esportsMatchId": str(details.get("id") or event["id"]),
        "startTime": event["startTime"],
        "bestOf": best_of,
        "numberOfGames": best_of,
        "blockName": event.get("blockName", ""),
        "league": {
            "id": str(league.get("id", "")),
            "name": league.get("name", ""),
            "slug": league.get("slug", ""),
            "image": (league.get("image") or "").replace("http://", "https://"),
        },
        "teams": teams,
    }


def upsert_scheduled_match(event: dict) -> None:
    doc = match_document_from_event(event)
    match_id = doc["esportsMatchId"]
    batch = db().batch()
    batch.set(match_ref(match_id), {**doc, "state": SCHEDULED, "updatedAt": firestore.SERVER_TIMESTAMP}, merge=True)
    games = event.get("games") or (event.get("match") or {}).get("games") or []
    for game in games:
        batch.set(
            game_ref(match_id, str(game["id"])),
            {"esportsGameId": str(game["id"]), "number": int(game.get("number", 0)), "state": "unstarted"},
            merge=True,
        )
    batch.commit()


def mark_match_live(event: dict, match: Match) -> None:
    """Passa el partit a ``live`` i refresca els equips (abans quedaven com a "TBD")."""
    doc = match_document_from_event(event)
    match_ref(match.match_id).set(
        {
            **doc,
            "state": LIVE,
            "score": match.wins(),
            "engine": match.to_dict(),
            "updatedAt": firestore.SERVER_TIMESTAMP,
        },
        merge=True,
    )


def load_match(match_id: str) -> Optional[Match]:
    data = get_match(match_id)
    if not data or "engine" not in data:
        return None
    return Match.from_dict(data["engine"])


def save_match(match: Match, completed: bool = False) -> None:
    update = {
        "engine": match.to_dict(),
        "score": match.wins(),
        "winnerId": match.winner,
        "updatedAt": firestore.SERVER_TIMESTAMP,
    }
    if completed:
        update["state"] = COMPLETED
    match_ref(match.match_id).set(update, merge=True)


def delete_match(match_id: str) -> None:
    """Esborra un partit programat que ha desaparegut o s'ha ajornat (i els seus jocs)."""
    ref = match_ref(match_id)
    _delete_documents(doc.reference for doc in ref.collection(GAMES).stream())
    ref.delete()


def _delete_documents(refs: Iterable) -> None:
    batch, count = db().batch(), 0
    for ref in refs:
        batch.delete(ref)
        count += 1
        if count == BATCH_LIMIT:
            batch.commit()
            batch, count = db().batch(), 0
    if count:
        batch.commit()


# --------------------------------------------------------------------------- jocs


def load_game(match_id: str, game_id: str) -> Optional[Game]:
    snapshot = game_ref(match_id, game_id).get()
    data = snapshot.to_dict() if snapshot.exists else None
    if not data or "engine" not in data:
        return None
    return Game.from_dict(data["engine"])


def save_game(game: Game, events: list[GameEvent]) -> None:
    """Desa l'estat del joc i els esdeveniments nous en un sol lot."""
    state = "completed" if game.winner else "inProgress"
    ids = game.team_ids()
    batch = db().batch()
    batch.set(
        game_ref(game.match_id, game.game_id),
        {
            "esportsGameId": game.game_id,
            "state": state,
            "winnerId": game.winner,
            "blueTeamId": ids["blue"],
            "redTeamId": ids["red"],
            "blueTeam": game.teams["blue"].name,
            "redTeam": game.teams["red"].name,
            "playingSeconds": int(game.playing_seconds),
            "paused": game.is_paused,
            "stats": game.current_stats(),
            "engine": game.to_dict(),
            "updatedAt": firestore.SERVER_TIMESTAMP,
        },
        merge=True,
    )
    notifications = game_ref(game.match_id, game.game_id).collection(NOTIFICATIONS)
    for event in events[:BATCH_LIMIT]:
        batch.set(notifications.document(event.doc_id), {**event.to_firestore(), "createdAt": firestore.SERVER_TIMESTAMP})
    batch.commit()
    if len(events) > BATCH_LIMIT:  # gairebé impossible, però sense perdre res
        for chunk_start in range(BATCH_LIMIT, len(events), BATCH_LIMIT):
            extra = db().batch()
            for event in events[chunk_start:chunk_start + BATCH_LIMIT]:
                extra.set(notifications.document(event.doc_id), event.to_firestore())
            extra.commit()
