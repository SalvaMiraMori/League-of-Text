import copy
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

BLUE_ID = "100"
RED_ID = "200"


def participant_meta(pid, name):
    return {"participantId": pid, "summonerName": name, "esportsPlayerId": f"p{pid}", "championId": "Ahri", "role": "mid"}


def team_frame(kills=None, deaths=None, towers=0, inhibitors=0, barons=0, dragons=None, gold=2500, offset=0):
    kills = kills or {}
    deaths = deaths or {}
    participants = [
        {"participantId": pid, "kills": kills.get(pid, 0), "deaths": deaths.get(pid, 0), "assists": 0}
        for pid in range(1 + offset, 6 + offset)
    ]
    return {
        "totalGold": gold,
        "inhibitors": inhibitors,
        "towers": towers,
        "barons": barons,
        "totalKills": sum(p["kills"] for p in participants),
        "dragons": list(dragons or []),
        "participants": participants,
    }


def frame(ts, state="in_game", blue=None, red=None):
    return {
        "rfc460Timestamp": ts,
        "gameState": state,
        "blueTeam": blue or team_frame(),
        "redTeam": red or team_frame(offset=5),
    }


@pytest.fixture
def window():
    return {
        "esportsGameId": "g1",
        "gameMetadata": {
            "blueTeamMetadata": {
                "esportsTeamId": BLUE_ID,
                "participantMetadata": [participant_meta(i, f"T1 Player{i}") for i in range(1, 6)],
            },
            "redTeamMetadata": {
                "esportsTeamId": RED_ID,
                "participantMetadata": [participant_meta(i, f"GEN Player{i}") for i in range(6, 11)],
            },
        },
        "frames": [frame("2026-11-14T08:00:00.000Z")],
    }


@pytest.fixture
def make_frame():
    return frame


@pytest.fixture
def make_team():
    return team_frame


@pytest.fixture
def deepcopy():
    return copy.deepcopy
