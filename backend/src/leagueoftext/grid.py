"""Client de l'API oficial de GRID (Riot Games Open Access).

* Central Data (``/central-data/graphql``): calendari, equips i tornejos.
* Series State (``/live-data-feed/series-state/graphql``): estat d'una sèrie, en directe o final.

Totes dues són GraphQL i necessiten la capçalera ``x-api-key`` (variable ``GRID_API_KEY``).

Aquest mòdul exposa les **mateixes funcions que** :mod:`leagueoftext.lolesports` i retorna les
dades amb la mateixa forma (esdeveniments del calendari, finestres de ``livestats``…). Així
la resta del backend (``Match``, ``Game``, Firestore, l'app) no canvia. Vegeu ``docs/GRID_API.md``.

Diferències amb l'API de LoL Esports:

* GRID no dona els ids dels jocs abans que comencin. Es fan servir ids sintètics
  ``<sèrie>-<número>`` (p. ex. ``3012986-2``), coneguts des del calendari.
* No hi ha finestres de temps: cada petició retorna l'estat actual sencer. :func:`get_game_window`
  el converteix en una finestra d'un sol fotograma.
* Open Access té 5 minuts de retard i un límit de 6 peticions per minut per sèrie.
"""
from __future__ import annotations

import logging
import re
import time
from datetime import datetime, timedelta, timezone
from functools import lru_cache
from typing import Iterator, Optional

import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

from . import config
from .lolesports import format_timestamp, format_window_time, next_window_start, parse_timestamp  # noqa: F401

log = logging.getLogger(__name__)

CENTRAL_DATA_URL = "https://api-op.grid.gg/central-data/graphql"
SERIES_STATE_URL = "https://api-op.grid.gg/live-data-feed/series-state/graphql"
TIMEOUT = (5, 20)
PAGE_SIZE = 50
MAX_PAGES = 20
LOL_TITLE_NAME = "league of legends"

#: Quantes vegades llegeix l'estat ``UpdateGameState`` a cada execució (una per minut) i cada
#: quants segons. 4 lectures + la del guanyador queden per sota del límit de 6/min per sèrie.
POLLS_PER_RUN = 4
POLL_INTERVAL_S = 10

#: Una lectura de Series State es reaprofita durant aquests segons (p. ex. per saber el
#: guanyador just després d'haver vist que el joc ha acabat), per estalviar peticions.
STATE_CACHE_S = 5

_session: Optional[requests.Session] = None
_state_cache: dict[tuple[str, str], tuple[float, dict | None]] = {}


class GridError(RuntimeError):
    pass


# --------------------------------------------------------------------------- consultes

TITLES_QUERY = """
query Titles {
  titles { id name }
}
"""

SERIES_FIELDS = """
  id
  startTimeScheduled
  format { nameShortened }
  tournament { id name nameShortened logoUrl parent { id name } }
  teams { baseInfo { id name nameShortened logoUrl } }
"""

ALL_SERIES_QUERY = """
query Schedule($titleId: ID!, $from: String!, $to: String!, $after: String) {
  allSeries(
    first: %d
    after: $after
    filter: { titleIds: { in: [$titleId] }, types: [ESPORTS], startTimeScheduled: { gte: $from, lte: $to } }
    orderBy: StartTimeScheduled
    orderDirection: ASC
  ) {
    pageInfo { hasNextPage endCursor }
    edges { node { %s } }
  }
}
""" % (PAGE_SIZE, SERIES_FIELDS)

SERIES_QUERY = """
query Series($id: ID!) {
  series(id: $id) { %s }
}
""" % SERIES_FIELDS

SERIES_STATE_QUERY = """
query SeriesState($id: ID!) {
  seriesState(id: $id) {
    id
    started
    finished
    valid
    updatedAt
    teams { id name score won }
    games {
      id
      sequenceNumber
      started
      finished
      paused
      startedAt
      clock { currentSeconds }
      teams {
        id
        name
        side
        won
        kills
        deaths
        objectives { type completionCount completedFirst }
        ... on GameTeamStateLol { totalMoneyEarned }
        players {
          id
          name
          character { name }
          kills
          deaths
          killAssistsGiven
          firstKill
          multikills { numberOfKills count }
          ... on GamePlayerStateLol { totalMoneyEarned }
        }
      }
    }
  }
}
"""

# --------------------------------------------------------------------------- client HTTP


def _get_session() -> requests.Session:
    global _session
    if _session is None:
        retry = Retry(
            total=3,
            backoff_factor=0.5,
            status_forcelist=(500, 502, 503, 504),
            allowed_methods=("POST",),  # són consultes de lectura: es poden repetir
            raise_on_status=False,
        )
        session = requests.Session()
        session.mount("https://", HTTPAdapter(max_retries=retry, pool_connections=2, pool_maxsize=4))
        _session = session
    return _session


def graphql(url: str, query: str, variables: dict | None = None) -> dict:
    response = _get_session().post(
        url,
        json={"query": query, "variables": variables or {}},
        headers={"x-api-key": config.grid_api_key()},
        timeout=TIMEOUT,
    )
    if response.status_code == 429:
        raise GridError("GRID: límit de peticions superat (HTTP 429)")
    try:
        body = response.json()
    except ValueError:
        raise GridError(f"GRID: HTTP {response.status_code} sense JSON")
    errors = body.get("errors")
    if errors:
        detail = "; ".join(
            f"{(e.get('extensions') or {}).get('errorType', 'ERROR')}: {e.get('message')}" for e in errors
        )
        raise GridError(f"GRID: {detail}")
    if response.status_code != 200:
        raise GridError(f"GRID: HTTP {response.status_code}")
    return body.get("data") or {}


@lru_cache(maxsize=1)
def lol_title_id() -> str:
    """Id de League of Legends a GRID (variable ``GRID_TITLE_ID`` o consulta a ``titles``)."""
    configured = config.grid_title_id()
    if configured:
        return configured
    for title in graphql(CENTRAL_DATA_URL, TITLES_QUERY).get("titles", []):
        if (title.get("name") or "").strip().lower() == LOL_TITLE_NAME:
            return str(title["id"])
    raise GridError("GRID: no s'ha trobat el títol League of Legends")


# --------------------------------------------------------------------------- ids de joc


def game_id_for(series_id: str, number: int) -> str:
    return f"{series_id}-{number}"


def split_game_id(game_id: str) -> tuple[str, int]:
    series_id, _, number = str(game_id).rpartition("-")
    if not series_id or not number.isdigit():
        raise ValueError(f"Id de joc de GRID no vàlid: {game_id}")
    return series_id, int(number)


# --------------------------------------------------------------------------- calendari


def best_of(series: dict) -> int:
    """``Bo5`` -> 5. Si el format és desconegut, 1."""
    match = re.search(r"(\d+)", ((series.get("format") or {}).get("nameShortened") or ""))
    return int(match.group(1)) if match else 1


def league_for(tournament: dict, leagues: tuple[str, ...] | None = None) -> str | None:
    """Lliga seguida a què pertany un torneig de GRID.

    GRID anomena els tornejos ``"Worlds - 2026 (Swiss: Swiss)"``: es mira si el nom (o el del
    torneig pare) comença pel nom d'una de les lligues de ``config.leagues()``.
    """
    names = [tournament.get("name") or "", ((tournament.get("parent") or {}).get("name") or "")]
    for league in leagues or config.leagues():
        prefix = league.strip().lower()
        for name in names:
            lowered = name.strip().lower()
            if lowered == prefix or re.match(rf"{re.escape(prefix)}\b", lowered):
                return league
    return None


def league_slug(name: str) -> str:
    return re.sub(r"[^a-z0-9]+", "_", name.lower()).strip("_")


def series_to_event(series: dict, state: str, leagues: tuple[str, ...] | None = None) -> dict:
    """Converteix una sèrie de Central Data en un esdeveniment amb la forma de ``all/schedule``."""
    series_id = str(series["id"])
    count = best_of(series)
    tournament = series.get("tournament") or {}
    league = league_for(tournament, leagues) or tournament.get("name", "")
    teams = []
    for participant in series.get("teams") or []:
        info = participant.get("baseInfo") or {}
        teams.append(
            {
                "id": str(info.get("id", "")),
                "code": info.get("nameShortened") or info.get("name") or "TBD",
                "name": info.get("name") or "TBD",
                "image": info.get("logoUrl") or "",
            }
        )
    return {
        "id": series_id,
        "type": "match",
        "state": state,
        "startTime": format_timestamp(parse_timestamp(series["startTimeScheduled"])),
        "blockName": tournament.get("nameShortened") or "",
        "league": {"id": str(tournament.get("id", "")), "name": league, "slug": league_slug(league), "image": ""},
        "match": {
            "id": series_id,
            "strategy": {"type": "bestOf", "count": count},
            "teams": teams,
            "games": [{"id": game_id_for(series_id, n), "number": n} for n in range(1, count + 1)],
        },
    }


def iter_schedule_events(direction: str = "newer", now: datetime | None = None) -> Iterator[dict]:
    """Sèries de LoL des de fa un dia fins a l'horitzó de ``config.schedule_horizon_days()``.

    ``direction`` s'accepta per compatibilitat amb :mod:`lolesports` (només es fa servir ``newer``).
    Les que encara no han arribat a l'hora d'inici surten com a ``unstarted``.
    """
    now = now or datetime.now(timezone.utc)
    variables = {
        "titleId": lol_title_id(),
        "from": format_timestamp(now - timedelta(days=1)),
        "to": format_timestamp(now + timedelta(days=config.schedule_horizon_days())),
    }
    after = None
    for _ in range(MAX_PAGES):
        data = graphql(CENTRAL_DATA_URL, ALL_SERIES_QUERY, {**variables, "after": after})["allSeries"]
        for edge in data["edges"]:
            series = edge["node"]
            started = parse_timestamp(series["startTimeScheduled"]) <= now
            yield series_to_event(series, "inProgress" if started else "unstarted")
        if not data["pageInfo"]["hasNextPage"]:
            return
        after = data["pageInfo"]["endCursor"]
    log.warning("S'ha arribat al límit de %d pàgines del calendari de GRID", MAX_PAGES)


# --------------------------------------------------------------------------- estat de la sèrie


def get_series_state(series_id: str, max_age: float = 0) -> dict | None:
    """``seriesState`` d'una sèrie, o ``None`` si GRID encara no en té."""
    key = (SERIES_STATE_URL, str(series_id))
    cached = _state_cache.get(key)
    if max_age and cached and time.monotonic() - cached[0] <= max_age:
        return cached[1]
    state = graphql(SERIES_STATE_URL, SERIES_STATE_QUERY, {"id": str(series_id)}).get("seriesState")
    _state_cache[key] = (time.monotonic(), state)
    return state


def get_live_event(match_id: str) -> dict | None:
    """L'esdeveniment del partit si ja ha començat i no ha acabat (amb ``state = inProgress``)."""
    try:
        state = get_series_state(match_id)
    except (GridError, requests.RequestException) as exc:
        log.warning("No s'ha pogut llegir l'estat de la sèrie %s: %s", match_id, exc)
        return None
    if not state or not state.get("started") or state.get("finished"):
        return None
    series = graphql(CENTRAL_DATA_URL, SERIES_QUERY, {"id": str(match_id)}).get("series")
    return series_to_event(series, "inProgress") if series else None


def get_live_events() -> list[dict]:
    """GRID no té cap consulta de «partits en directe»: fes servir :func:`get_live_event`."""
    raise NotImplementedError("GRID no té llista de partits en directe; fes servir get_live_event(match_id)")


def get_event_details(match_id: str) -> dict | None:
    try:
        series = graphql(CENTRAL_DATA_URL, SERIES_QUERY, {"id": str(match_id)}).get("series")
    except (GridError, requests.RequestException) as exc:
        log.warning("No s'han pogut obtenir els detalls de la sèrie %s: %s", match_id, exc)
        return None
    return series_to_event(series, "unstarted") if series else None


# --------------------------------------------------------------------------- jocs


def _find_game(state: dict | None, number: int) -> dict | None:
    for game in (state or {}).get("games") or []:
        if int(game.get("sequenceNumber", 0)) == number:
            return game
    return None


def _objective_counts(team: dict) -> dict[str, int]:
    return {o["type"]: int(o.get("completionCount", 0)) for o in team.get("objectives") or []}


#: ``slayOceanDrake`` -> ``ocean``; ``slayElderDragon`` -> ``elder`` (com al feed de LoL Esports).
_DRAKE = re.compile(r"^slay(\w+)Drake$")
DRAGON_ORDER = ("infernal", "ocean", "cloud", "mountain", "hextech", "chemtech")


def dragon_counts(objectives: dict[str, int]) -> dict[str, int]:
    counts: dict[str, int] = {}
    for kind, count in objectives.items():
        match = _DRAKE.match(kind)
        if match and count:
            counts[match.group(1).lower()] = count
    if objectives.get("slayElderDragon"):
        counts["elder"] = objectives["slayElderDragon"]
    return counts


def merge_dragons(previous: list[str], counts: dict[str, int]) -> list[str]:
    """Llista ordenada de dracs: conserva l'ordre ja conegut i afegeix al final els nous.

    GRID només dona quants dracs de cada tipus porta l'equip, però ``Game`` necessita la llista
    en ordre (per anunciar el drac nou i detectar l'ànima, el quart drac elemental).
    """
    result = list(previous)
    known = {kind: result.count(kind) for kind in set(result)}
    for kind in (*DRAGON_ORDER, *sorted(k for k in counts if k not in DRAGON_ORDER and k != "elder"), "elder"):
        result.extend([kind] * max(0, counts.get(kind, 0) - known.get(kind, 0)))
    return result


def _ordered_players(team: dict) -> list[dict]:
    # Ordre estable entre lectures: els participantId (1-5 i 6-10) depenen d'aquest ordre.
    return sorted(team.get("players") or [], key=lambda p: str(p.get("id", "")))


def _team_frame(team: dict, previous: dict | None, offset: int) -> dict:
    objectives = _objective_counts(team)
    first_tower = any(
        o.get("type") == "destroyTower" and o.get("completedFirst") for o in team.get("objectives") or []
    )
    participants = []
    for index, player in enumerate(_ordered_players(team), start=1 + offset):
        participants.append(
            {
                "participantId": index,
                "esportsPlayerId": str(player.get("id", "")),
                "kills": int(player.get("kills", 0)),
                "deaths": int(player.get("deaths", 0)),
                "assists": int(player.get("killAssistsGiven", 0)),
                "totalGold": int(player.get("totalMoneyEarned", 0)),
                "firstKill": bool(player.get("firstKill")),
                "multikills": {
                    str(m["numberOfKills"]): int(m.get("count", 0)) for m in player.get("multikills") or []
                },
            }
        )
    return {
        "esportsTeamId": str(team.get("id", "")),
        "totalGold": int(team.get("totalMoneyEarned", 0)),
        "totalKills": int(team.get("kills", 0)),
        "towers": objectives.get("destroyTower", 0),
        "firstTower": first_tower,
        "inhibitors": objectives.get("destroyFortifier", 0),
        "barons": objectives.get("slayBaron", 0),
        "dragons": merge_dragons((previous or {}).get("dragons", []), dragon_counts(objectives)),
        "participants": participants,
    }


def _team_metadata(team: dict, offset: int) -> dict:
    return {
        "esportsTeamId": str(team.get("id", "")),
        "participantMetadata": [
            {
                "participantId": index,
                "summonerName": player.get("name") or f"Player {index}",
                "esportsPlayerId": str(player.get("id", "")),
                "championId": (player.get("character") or {}).get("name", ""),
                "role": "",
            }
            for index, player in enumerate(_ordered_players(team), start=1 + offset)
        ],
    }


def game_to_window(state: dict, game: dict, previous_frame: dict | None = None) -> dict:
    """Converteix un joc de Series State en una finestra de ``livestats`` d'un sol fotograma."""
    sides = {(t.get("side") or "").lower(): t for t in game.get("teams") or []}
    blue, red = sides.get("blue"), sides.get("red")
    if blue is None or red is None:
        teams = game.get("teams") or []
        if len(teams) != 2:
            raise GridError(f"GRID: el joc {game.get('id')} no té dos equips")
        blue, red = teams
    if game.get("finished"):
        game_state = "finished"
    elif game.get("paused"):
        game_state = "paused"
    else:
        game_state = "in_game"
    previous_frame = previous_frame or {}
    frame = {
        # El rellotge de GRID dona el temps de joc real (sense pauses ni el retard d'Open Access).
        "rfc460Timestamp": state.get("updatedAt") or format_window_time(datetime.now(timezone.utc)),
        "clockSeconds": int(((game.get("clock") or {}).get("currentSeconds")) or 0),
        "gameState": game_state,
        "blueTeam": _team_frame(blue, previous_frame.get("blueTeam"), 0),
        "redTeam": _team_frame(red, previous_frame.get("redTeam"), 5),
    }
    return {
        "esportsGameId": game_id_for(str(state.get("id", "")), int(game.get("sequenceNumber", 0))),
        "gridGameId": game.get("id"),
        "gameMetadata": {"blueTeamMetadata": _team_metadata(blue, 0), "redTeamMetadata": _team_metadata(red, 5)},
        "frames": [frame],
    }


def get_game_window(
    game_id: str, starting_time: datetime | None = None, previous_frame: dict | None = None
) -> dict | None:
    """Estat actual del joc com a finestra de ``livestats``. ``None`` si encara no ha començat.

    ``starting_time`` s'ignora (GRID no té finestres de temps); ``previous_frame`` és l'últim
    fotograma processat i serveix per mantenir l'ordre dels dracs.
    """
    series_id, number = split_game_id(game_id)
    try:
        state = get_series_state(series_id)
    except (GridError, requests.RequestException) as exc:
        log.warning("Error llegint l'estat de la sèrie %s: %s", series_id, exc)
        return None
    game = _find_game(state, number)
    if game is None or not game.get("started"):
        return None
    return game_to_window(state, game, previous_frame)


def find_game_winner(match_id: str, game_id: str, known_wins: dict[str, int] | None = None) -> str | None:
    """Equip guanyador d'un joc acabat segons GRID (``won``), o ``None`` si encara no es coneix."""
    series_id, number = split_game_id(game_id)
    try:
        state = get_series_state(series_id, max_age=STATE_CACHE_S)
    except (GridError, requests.RequestException) as exc:
        log.warning("No s'ha pogut consultar el guanyador de %s: %s", game_id, exc)
        return None
    game = _find_game(state, number)
    if not game or not game.get("finished"):
        return None
    winner = next((t for t in game.get("teams") or [] if t.get("won")), None)
    return str(winner["id"]) if winner else None
