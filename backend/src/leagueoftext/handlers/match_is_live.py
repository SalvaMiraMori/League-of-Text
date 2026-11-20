"""MatchIsLive: comprova cada minut si un partit ha començat.

Quan la font de dades (GRID o LoL Esports) diu que el partit és en directe:

* el passa a ``state = live`` (refrescant els equips) i hi desa l'estat inicial;
* treu el seu objectiu de la regla ``MatchIsLive``;
* invoca ``UpdateMatchState``.

Si al cap de ``GIVE_UP_AFTER`` el partit no ha començat (ajornat o cancel·lat), deixa de
vigilar-lo i l'esborra: ``GetScheduledMatches`` el tornarà a afegir amb la nova hora.
"""
from __future__ import annotations

import logging
from datetime import timedelta

from .. import datasource, lolesports, store
from ..match import Match
from ..scheduling import MATCH_IS_LIVE, UPDATE_MATCH_STATE, Scheduler, utcnow

log = logging.getLogger()
log.setLevel(logging.INFO)

GIVE_UP_AFTER = timedelta(hours=6)


def lambda_handler(event, context):
    match_id = str(event["matchId"])
    scheduler = Scheduler.from_context(context)
    target_id = f"{MATCH_IS_LIVE}-{match_id}"

    existing = store.get_match(match_id)
    if existing and existing.get("state") in (store.LIVE, store.COMPLETED):
        # Execució duplicada: ja s'ha processat.
        scheduler.remove_polling_target(MATCH_IS_LIVE, target_id)
        return {"matchId": match_id, "live": True}

    live_event = datasource.api().get_live_event(match_id)

    if live_event is None:
        start = event.get("startTime") or (existing or {}).get("startTime")
        if start and utcnow() - lolesports.parse_timestamp(start) > GIVE_UP_AFTER:
            log.warning("El partit %s no ha començat després de %s: es deixa de vigilar", match_id, GIVE_UP_AFTER)
            scheduler.remove_polling_target(MATCH_IS_LIVE, target_id)
            store.delete_match(match_id)
        return {"matchId": match_id, "live": False}

    match = Match.from_event(live_event)
    store.mark_match_live(live_event, match)
    scheduler.remove_polling_target(MATCH_IS_LIVE, target_id)
    scheduler.invoke_async(UPDATE_MATCH_STATE, {"matchId": match_id})
    log.info("El partit %s (%s) és en directe", match_id, match.title())
    return {"matchId": match_id, "live": True}
