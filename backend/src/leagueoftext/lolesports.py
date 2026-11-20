"""Client de l'API (no oficial) de LoL Esports.

* ``esports-api.lolesports.com``: calendari, partits en directe i detalls d'un partit.
  Necessita la capçalera ``x-api-key`` (variable d'entorn ``LOLESPORTS_API_KEY``).
* ``feed.lolesports.com``: instantànies (``livestats``) d'un joc en curs. No necessita clau.

Totes les peticions comparteixen una sessió HTTP amb reintents limitats i *timeout*,
en lloc dels bucles infinits ``while True: try/except`` de la versió anterior.
"""
from __future__ import annotations

import logging
from datetime import datetime, timedelta, timezone
from typing import Iterator, Optional

import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

from . import config

log = logging.getLogger(__name__)

ESPORTS_API = "https://esports-api.lolesports.com/persisted"
LIVESTATS_API = "https://feed.lolesports.com/livestats/v1"
LANG = "en-US"
TIMEOUT = (5, 15)  # (connexió, lectura) en segons
MAX_SCHEDULE_PAGES = 40

_session: Optional[requests.Session] = None


class LolEsportsError(RuntimeError):
    pass


def _get_session() -> requests.Session:
    global _session
    if _session is None:
        retry = Retry(
            total=4,
            backoff_factor=0.5,
            status_forcelist=(429, 500, 502, 503, 504),
            allowed_methods=("GET",),
            raise_on_status=False,
        )
        session = requests.Session()
        adapter = HTTPAdapter(max_retries=retry, pool_connections=4, pool_maxsize=8)
        session.mount("https://", adapter)
        _session = session
    return _session


def _esports_get(path: str, **params) -> dict:
    params = {"hl": LANG, **{k: v for k, v in params.items() if v is not None}}
    response = _get_session().get(
        f"{ESPORTS_API}/{path}",
        params=params,
        headers={"x-api-key": config.lolesports_api_key()},
        timeout=TIMEOUT,
    )
    if response.status_code != 200:
        raise LolEsportsError(f"GET {path} -> HTTP {response.status_code}")
    return response.json()


# --------------------------------------------------------------------------- calendari


def get_schedule(page_token: str | None = None) -> dict:
    return _esports_get("all/schedule", pageToken=page_token)["data"]["schedule"]


def iter_schedule_events(direction: str = "newer") -> Iterator[dict]:
    """Recorre el calendari a partir de la pàgina actual cap a ``newer`` o ``older``.

    Té un límit de pàgines perquè una resposta inesperada no deixi la Lambda en un bucle.
    """
    token = None
    for _ in range(MAX_SCHEDULE_PAGES):
        schedule = get_schedule(token)
        yield from schedule.get("events", [])
        token = (schedule.get("pages") or {}).get(direction)
        if not token:
            return
    log.warning("S'ha arribat al límit de %d pàgines del calendari", MAX_SCHEDULE_PAGES)


def get_live_events() -> list[dict]:
    data = _esports_get("all/live")["data"]
    return (data.get("schedule") or data.get("live") or {}).get("events", []) or []


def get_live_event(match_id: str) -> dict | None:
    """L'esdeveniment del partit si és en directe (``state = inProgress``), o ``None``."""
    return next(
        (
            e
            for e in get_live_events()
            if str((e.get("match") or {}).get("id") or e.get("id")) == str(match_id) and e.get("state") == "inProgress"
        ),
        None,
    )


def get_event_details(match_id: str) -> dict | None:
    """Detalls d'un partit (equips, resultat i jocs). Retorna ``None`` si l'API no respon."""
    try:
        return _esports_get("gw/getEventDetails", id=match_id)["data"]["event"]
    except (LolEsportsError, requests.RequestException, KeyError, ValueError) as exc:
        log.warning("No s'han pogut obtenir els detalls del partit %s: %s", match_id, exc)
        return None


# --------------------------------------------------------------------------- livestats


def get_game_window(game_id: str, starting_time: datetime | None = None) -> dict | None:
    """Instantànies d'un joc. Retorna ``None`` si encara no n'hi ha (HTTP 204/400/404)."""
    params = {}
    if starting_time is not None:
        params["startingTime"] = format_window_time(starting_time)
    try:
        response = _get_session().get(f"{LIVESTATS_API}/window/{game_id}", params=params, timeout=TIMEOUT)
    except requests.RequestException as exc:
        log.warning("Error de xarxa llegint livestats de %s: %s", game_id, exc)
        return None
    if response.status_code != 200 or not response.content:
        return None
    try:
        body = response.json()
    except ValueError:
        return None
    if not body or "frames" not in body or not body["frames"]:
        return None
    return body


# --------------------------------------------------------------------------- guanyadors


def find_game_winner(match_id: str, game_id: str, known_wins: dict[str, int]) -> str | None:
    """Esbrina quin equip ha guanyat un joc acabat.

    1. Mira l'estat del joc a ``getEventDetails`` i, si cal, compara les victòries de cada
       equip amb les que ja teníem guardades (``known_wins``): l'equip que en té una més és
       el guanyador.
    2. Si l'API encara no s'ha actualitzat, retorna ``None`` i s'hi tornarà a provar a la
       següent execució (en lloc d'esperar en un bucle infinit).
    """
    event = get_event_details(match_id) or _find_event_in_recent_schedule(match_id)
    if not event:
        return None
    match = event.get("match") or {}

    for game in match.get("games", []) + event.get("games", []):
        if game.get("id") != game_id:
            continue
        for team in game.get("teams", []):
            outcome = ((team.get("result") or {}).get("outcome") or "").lower()
            if outcome == "win":
                return team.get("id")

    for team in match.get("teams", []):
        wins = ((team.get("result") or {}).get("gameWins"))
        team_id = team.get("id")
        if team_id is not None and wins is not None and wins > known_wins.get(team_id, 0):
            return team_id
    return None


def _find_event_in_recent_schedule(match_id: str, max_pages: int = 3) -> dict | None:
    """Alternativa (la de la versió original): buscar el partit a les pàgines recents del calendari."""
    token = None
    try:
        for _ in range(max_pages):
            schedule = get_schedule(token)
            for event in schedule.get("events", []):
                if event.get("id") == match_id or (event.get("match") or {}).get("id") == match_id:
                    return event
            token = (schedule.get("pages") or {}).get("older")
            if not token:
                break
    except (LolEsportsError, requests.RequestException, KeyError, ValueError) as exc:
        log.warning("No s'ha pogut consultar el calendari: %s", exc)
    return None


# --------------------------------------------------------------------------- dates


def parse_timestamp(value: str) -> datetime:
    """Converteix ``2022-10-01T08:12:30.123Z`` o ``2022-10-01T08:12:30Z`` a ``datetime`` UTC."""
    value = value.strip()
    if value.endswith("Z"):
        value = value[:-1] + "+00:00"
    # Python < 3.11 no accepta fraccions de segon que no tinguin 3 o 6 xifres.
    if "." in value:
        main, rest = value.split(".", 1)
        digits = "".join(ch for ch in rest if ch.isdigit())
        tz = rest[len(digits):]
        value = f"{main}.{(digits + '000000')[:6]}{tz}"
    parsed = datetime.fromisoformat(value)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def format_timestamp(value: datetime) -> str:
    return value.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def format_window_time(value: datetime) -> str:
    return value.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.000Z")


def next_window_start(frame_timestamp: str | datetime) -> datetime:
    """El feed només accepta ``startingTime`` múltiples de 10 s: arrodoneix cap avall i suma 10 s."""
    when = parse_timestamp(frame_timestamp) if isinstance(frame_timestamp, str) else frame_timestamp
    floored = when.replace(microsecond=0) - timedelta(seconds=when.second % 10)
    return floored + timedelta(seconds=10)
