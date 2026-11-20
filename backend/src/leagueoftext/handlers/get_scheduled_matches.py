"""GetScheduledMatches: recorre el calendari i programa els partits de les properes setmanes.

S'executa periòdicament (p. ex. cada 6 hores, vegeu ``template.yaml``). Per a cada partit
de les lligues seguides que encara no ha començat:

* el desa (o l'actualitza, si han canviat l'hora o els equips) amb ``state = scheduled``;
* programa la regla ``MatchIsLiveScheduler-<id>`` una hora abans de l'inici, o crida
  directament ``MatchIsLiveScheduler`` si el partit és imminent.
"""
from __future__ import annotations

import logging
from datetime import timedelta

from .. import config, datasource, lolesports, store
from ..scheduling import MATCH_IS_LIVE_SCHEDULER, Scheduler, is_soon, rule_name, utcnow

log = logging.getLogger()
log.setLevel(logging.INFO)


def _event_id(event: dict) -> str:
    return str((event.get("match") or {}).get("id") or event["id"])


def lambda_handler(event, context):
    scheduler = Scheduler.from_context(context)
    leagues = set(config.leagues())
    horizon = utcnow() + timedelta(days=config.schedule_horizon_days())
    watch_before = timedelta(minutes=config.watch_before_minutes())
    scheduled, updated, skipped = 0, 0, 0

    for api_event in datasource.api().iter_schedule_events("newer"):
        if api_event.get("type", "match") != "match":
            continue
        if (api_event.get("league") or {}).get("name") not in leagues:
            continue
        if api_event.get("state") != "unstarted":
            continue
        start = lolesports.parse_timestamp(api_event["startTime"])
        if start > horizon:
            continue

        match_id = _event_id(api_event)
        existing = store.get_match(match_id)
        if existing and existing.get("state") != store.SCHEDULED:
            skipped += 1
            continue

        new_doc = store.match_document_from_event(api_event)
        changed = existing is None or any(existing.get(k) != new_doc[k] for k in ("startTime", "teams", "bestOf"))
        if not changed:
            skipped += 1
            continue

        store.upsert_scheduled_match(api_event)
        payload = {"matchId": match_id, "startTime": api_event["startTime"]}
        watch_at = start - watch_before
        if is_soon(watch_at):
            scheduler.invoke_async(MATCH_IS_LIVE_SCHEDULER, payload)
        else:
            # put_rule amb el mateix nom també serveix per reprogramar un partit ajornat.
            scheduler.schedule_once(
                rule_name(f"{MATCH_IS_LIVE_SCHEDULER}-{match_id}"), watch_at, MATCH_IS_LIVE_SCHEDULER, payload
            )
        if existing is None:
            scheduled += 1
        else:
            updated += 1

    log.info("Partits nous: %d, actualitzats: %d, sense canvis: %d", scheduled, updated, skipped)
    return {"scheduled": scheduled, "updated": updated, "skipped": skipped}
