"""MatchIsLiveScheduler: s'executa una hora abans d'un partit.

Esborra la seva regla *one-shot* i afegeix el partit com a objectiu de la regla
``MatchIsLive`` (cada minut) perquè es comprovi quan comença.
"""
from __future__ import annotations

import logging

from ..scheduling import MATCH_IS_LIVE, MATCH_IS_LIVE_SCHEDULER, Scheduler, rule_name

log = logging.getLogger()
log.setLevel(logging.INFO)


def lambda_handler(event, context):
    match_id = str(event["matchId"])
    scheduler = Scheduler.from_context(context)

    scheduler.delete_rule(rule_name(f"{MATCH_IS_LIVE_SCHEDULER}-{match_id}"))
    scheduler.add_polling_target(
        MATCH_IS_LIVE,
        f"{MATCH_IS_LIVE}-{match_id}",
        {"matchId": match_id, "startTime": event.get("startTime")},
    )
    log.info("Partit %s afegit a %s", match_id, MATCH_IS_LIVE)
    return {"matchId": match_id}
