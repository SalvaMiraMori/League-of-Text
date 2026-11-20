"""Explorador de l'API de GRID (Riot Games Open Access) per a League of Text.

Executa les consultes de ``docs/GRID_API.md`` i desa les respostes en JSON, per veure
quines dades arriben realment abans de connectar-les al backend.

La clau es llegeix de la variable d'entorn ``GRID_API_KEY`` o, si no hi és, d'un fitxer
``.env`` a l'arrel del repositori (ignorat per git) amb una línia ``GRID_API_KEY=...``.

Exemples (des de ``backend/``)::

    python scripts/grid_explore.py titles
    python scripts/grid_explore.py tournaments --name World
    python scripts/grid_explore.py series --title-id <id de LoL> --tournament World
    python scripts/grid_explore.py result 2881666
    python scripts/grid_explore.py detail 2881666 --out worlds_final.json

Límits d'Open Access: Central Data 20 peticions/minut; Series State 180/minut en total i
6/minut per sèrie. L'script espera entre peticions per no passar-se'n.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path

import requests

CENTRAL_DATA_URL = "https://api-op.grid.gg/central-data/graphql"
SERIES_STATE_URL = "https://api-op.grid.gg/live-data-feed/series-state/graphql"
TIMEOUT = (5, 30)

# Pausa mínima entre peticions (segons) per respectar els límits d'Open Access.
MIN_INTERVAL = {CENTRAL_DATA_URL: 3.1, SERIES_STATE_URL: 0.4}

# --------------------------------------------------------------------------- consultes
# Central Data: dades estàtiques (títols, tornejos, equips, calendari).

TITLES = """
query Titles {
  titles {
    id
    name
    nameShortened
  }
}
"""

TOURNAMENTS = """
query Tournaments($name: String!, $after: String) {
  tournaments(
    first: 50
    after: $after
    filter: { name: { contains: $name } }
    orderDirection: ASC
  ) {
    totalCount
    pageInfo { hasNextPage endCursor }
    edges {
      node {
        id
        name
        nameShortened
        startDate
        endDate
        logoUrl
        titles { id name }
        parent { id name }
        children { id name }
      }
    }
  }
}
"""

SERIES = """
query TournamentSeries(
  $titleId: ID!
  $tournamentName: String!
  $from: String!
  $to: String!
  $after: String
) {
  allSeries(
    first: 50
    after: $after
    filter: {
      titleIds: { in: [$titleId] }
      types: [ESPORTS]
      tournament: { name: { contains: $tournamentName }, includeChildren: { equals: true } }
      startTimeScheduled: { gte: $from, lte: $to }
    }
    orderBy: StartTimeScheduled
    orderDirection: ASC
  ) {
    totalCount
    pageInfo { hasNextPage endCursor }
    edges {
      node {
        id
        startTimeScheduled
        updatedAt
        format { name nameShortened }
        tournament { id name nameShortened logoUrl parent { id name } }
        teams {
          scoreAdvantage
          baseInfo { id name nameShortened logoUrl colorPrimary colorSecondary }
        }
        streams { url }
        productServiceLevels { productName serviceLevel }
      }
    }
  }
}
"""

# Series State: estat d'una sèrie (en directe o final).

SERIES_RESULT = """
query SeriesResult($id: ID!) {
  seriesState(id: $id) {
    id
    version
    format
    started
    finished
    forfeited
    valid
    startedAt
    updatedAt
    duration
    teams { id name score won }
    games {
      id
      sequenceNumber
      started
      finished
      paused
      startedAt
      duration
      teams { id name side won }
    }
  }
}
"""

SERIES_DETAIL = """
query SeriesDetail($id: ID!) {
  seriesState(id: $id) {
    id
    format
    started
    finished
    valid
    updatedAt
    teams {
      id
      name
      score
      won
      kills
      deaths
      players { id name kills deaths killAssistsGiven }
    }
    games {
      id
      sequenceNumber
      started
      finished
      paused
      startedAt
      duration
      titleVersion { name }
      clock { currentSeconds ticking }
      draftActions {
        sequenceNumber
        type
        drafter { id type }
        draftable { id type name }
      }
      structures { id type side teamId destroyed currentHealth maxHealth }
      nonPlayerCharacters { id type side alive respawnClock { currentSeconds } }
      teams {
        id
        name
        side
        won
        kills
        deaths
        netWorth
        firstKill
        structuresDestroyed
        objectives { id type completionCount completedFirst }
        unitKills { unitName count }
        multikills { numberOfKills count }
        ... on GameTeamStateLol {
          totalMoneyEarned
          moneyDifference
          experiencePoints
          damageDealt
          visionScore
          baronPowerPlays { id value }
        }
        players {
          id
          name
          participationStatus
          character { id name }
          kills
          deaths
          killAssistsGiven
          netWorth
          firstKill
          inventory { items { id name quantity } }
          multikills { numberOfKills count }
          ... on GamePlayerStateLol {
            roles { id }
            alive
            currentHealth
            maxHealth
            experiencePoints
            totalMoneyEarned
            damageDealt
            visionScore
            kdaRatio
            killParticipation
          }
        }
      }
    }
  }
}
"""

QUERIES = {
    "titles": TITLES,
    "tournaments": TOURNAMENTS,
    "series": SERIES,
    "result": SERIES_RESULT,
    "detail": SERIES_DETAIL,
}

# --------------------------------------------------------------------------- client


class GridError(RuntimeError):
    pass


def api_key() -> str:
    key = os.environ.get("GRID_API_KEY")
    if key:
        return key.strip()
    env_file = Path(__file__).resolve().parents[2] / ".env"
    if env_file.exists():
        for line in env_file.read_text(encoding="utf-8").splitlines():
            name, sep, value = line.partition("=")
            if sep and name.strip() == "GRID_API_KEY":
                return value.strip().strip('"').strip("'")
    raise GridError("Falta GRID_API_KEY (variable d'entorn o fitxer .env a l'arrel del repositori).")


_last_call: dict[str, float] = {}


def graphql(url: str, query: str, variables: dict | None = None) -> dict:
    wait = MIN_INTERVAL[url] - (time.monotonic() - _last_call.get(url, 0.0))
    if wait > 0:
        time.sleep(wait)
    _last_call[url] = time.monotonic()

    response = requests.post(
        url,
        json={"query": query, "variables": variables or {}},
        headers={"x-api-key": api_key(), "Content-Type": "application/json"},
        timeout=TIMEOUT,
    )
    if response.status_code == 429:
        raise GridError("HTTP 429: s'ha superat el límit de peticions. Espera un minut i torna-hi.")
    try:
        body = response.json()
    except ValueError:
        raise GridError(f"HTTP {response.status_code}: resposta no JSON: {response.text[:300]}")
    if body.get("errors"):
        details = []
        for err in body["errors"]:
            ext = err.get("extensions") or {}
            details.append(f"{ext.get('errorType') or ext.get('code') or 'ERROR'}: {err.get('message')}")
        raise GridError(" | ".join(details))
    if response.status_code != 200:
        raise GridError(f"HTTP {response.status_code}")
    return body["data"]


def paginate(url: str, query: str, field: str, variables: dict, max_pages: int = 20) -> list[dict]:
    """Recorre totes les pàgines (50 resultats per pàgina) d'una connexió ``edges/pageInfo``."""
    nodes: list[dict] = []
    after = None
    for _ in range(max_pages):
        data = graphql(url, query, {**variables, "after": after})[field]
        nodes.extend(edge["node"] for edge in data["edges"])
        if not data["pageInfo"]["hasNextPage"]:
            break
        after = data["pageInfo"]["endCursor"]
    return nodes


# --------------------------------------------------------------------------- ordres


def run(args: argparse.Namespace):
    if args.command == "titles":
        return graphql(CENTRAL_DATA_URL, TITLES)["titles"]
    if args.command == "tournaments":
        return paginate(CENTRAL_DATA_URL, TOURNAMENTS, "tournaments", {"name": args.name})
    if args.command == "series":
        return paginate(
            CENTRAL_DATA_URL,
            SERIES,
            "allSeries",
            {
                "titleId": args.title_id,
                "tournamentName": args.tournament,
                "from": args.date_from,
                "to": args.date_to,
            },
        )
    if args.command in ("result", "detail"):
        return graphql(SERIES_STATE_URL, QUERIES[args.command], {"id": args.series_id})["seriesState"]
    raise GridError(f"Ordre desconeguda: {args.command}")


def summarize(command: str, data) -> str:
    """Resum curt per pantalla; el JSON complet va a ``--out``."""
    if command == "titles":
        return "\n".join(f"{t['id']:>4}  {t['name']} ({t['nameShortened']})" for t in data)
    if command == "tournaments":
        return "\n".join(
            f"{t['id']:>8}  {t['name']}  [{t.get('startDate')} → {t.get('endDate')}]"
            + (f"  pare: {t['parent']['name']}" if t.get("parent") else "")
            for t in data
        )
    if command == "series":
        lines = []
        for s in data:
            teams = " vs ".join(p["baseInfo"].get("nameShortened") or p["baseInfo"]["name"] for p in s["teams"])
            lines.append(f"{s['id']:>8}  {s['startTimeScheduled']}  {s['format']['nameShortened']:<4}  {teams}  · {s['tournament']['name']}")
        return "\n".join(lines) + f"\n{len(data)} sèries"
    if data is None:
        return "Sense dades (la sèrie no existeix o la clau no hi té accés)."
    teams = " - ".join(f"{t['name']} {t['score']}" for t in data["teams"])
    state = "acabada" if data["finished"] else "en directe" if data["started"] else "no començada"
    return f"Sèrie {data['id']} ({data['format']}, {state}, valid={data['valid']}): {teams}, {len(data['games'])} partides"


def main(argv: list[str] | None = None) -> int:
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--out", help="Desa la resposta completa en aquest fitxer JSON")
    parser = argparse.ArgumentParser(description="Explorador de l'API de GRID (Open Access)")
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("titles", parents=[common], help="Llista els títols (per trobar l'id de League of Legends)")

    p = sub.add_parser("tournaments", parents=[common], help="Cerca tornejos pel nom")
    p.add_argument("--name", default="World")

    p = sub.add_parser("series", parents=[common], help="Llista les sèries d'un torneig")
    p.add_argument("--title-id", required=True, help="Id de LoL obtingut amb 'titles'")
    p.add_argument("--tournament", default="World", help="Text que ha de contenir el nom del torneig")
    p.add_argument("--from", dest="date_from", default="2026-09-01T00:00:00Z")
    p.add_argument("--to", dest="date_to", default="2026-12-31T23:59:59Z")

    for name, text in (("result", "Marcador i partides d'una sèrie"), ("detail", "Estat complet d'una sèrie")):
        p = sub.add_parser(name, parents=[common], help=text)
        p.add_argument("series_id")

    args = parser.parse_args(argv)
    try:
        data = run(args)
    except (GridError, requests.RequestException) as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1

    print(summarize(args.command, data))
    if args.out:
        Path(args.out).write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")
        print(f"Resposta completa desada a {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
