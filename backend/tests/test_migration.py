import importlib.util
from datetime import datetime, timezone
from pathlib import Path

_spec = importlib.util.spec_from_file_location(
    "migrate", Path(__file__).resolve().parents[1] / "scripts" / "migrate_legacy_collections.py"
)
migrate = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(migrate)

NOW = datetime(2026, 11, 19, 12, tzinfo=timezone.utc)


def old_match(start, wins=(3, 1), necessary=3):
    return {
        "esportsMatchId": "m1",
        "startTime": start,
        "numberOfGames": 5,
        "teams": [{"id": "1", "code": "T1", "name": "T1"}, {"id": "2", "code": "GEN", "name": "Gen.G"}],
        "matchObjectJson": {
            "teams": {"1": {"name": "T1", "wins": wins[0]}, "2": {"name": "Gen.G", "wins": wins[1]}},
            "necessaryWins": necessary,
            "winner": {"name": "T1", "wins": wins[0]},
        },
    }


def test_completed_match_keeps_score_and_winner():
    doc = migrate.convert_match("CompletedMatches", old_match("2022-11-05T08:00:00Z"), NOW)
    assert doc["state"] == "completed"
    assert doc["bestOf"] == 5
    assert doc["score"] == {"1": 3, "2": 1}
    assert doc["winnerId"] == "1"
    assert "matchObjectJson" not in doc


def test_stale_live_match_becomes_completed():
    doc = migrate.convert_match("LiveMatches", old_match("2023-01-20T08:00:00Z", wins=(2, 1)), NOW)
    assert doc["state"] == "completed"
    assert doc["winnerId"] == "1"  # el que anava guanyant


def test_recent_live_match_stays_live_without_winner():
    doc = migrate.convert_match("LiveMatches", old_match("2026-11-19T10:00:00Z", wins=(1, 1)), NOW)
    assert doc["state"] == "live"
    assert "winnerId" not in doc


def test_past_scheduled_match_is_skipped_and_future_one_is_copied():
    assert migrate.convert_match("ScheduledMatches", {"startTime": "2023-01-01T00:00:00Z"}, NOW) is None
    doc = migrate.convert_match("ScheduledMatches", {"startTime": "2026-12-01T00:00:00Z", "numberOfGames": 3}, NOW)
    assert doc["state"] == "scheduled" and doc["bestOf"] == 3


def test_game_gets_winner_and_teams():
    game = {
        "esportsGameId": "g1",
        "state": "inProgress",
        "number": 1,
        "gameObjectJson": {
            "winner": "2",
            "teams": {"blueTeam": {"name": "T1", "esportsTeamId": "1"}, "redTeam": {"name": "GEN", "esportsTeamId": "2"}},
        },
    }
    doc = migrate.convert_game(game, "completed")
    assert doc["state"] == "completed" and doc["winnerId"] == "2"
    assert doc["blueTeam"] == "T1" and doc["redTeamId"] == "2"
    assert "gameObjectJson" not in doc


# --- El backend antic no desava la victòria de l'última partida -----------------------------


def test_last_game_win_is_restored_from_legacy_winner():
    """Bo5 acabat 3-2 però desat com 2-2 (amb el guanyador sí desat)."""
    doc = migrate.convert_match("CompletedMatches", old_match("2022-11-05T08:00:00Z", wins=(2, 2)), NOW)
    assert doc["score"] == {"1": 3, "2": 2}
    assert doc["winnerId"] == "1"


def test_bo3_won_by_second_team_is_restored():
    data = old_match("2022-11-05T08:00:00Z", wins=(1, 1), necessary=2)
    data["numberOfGames"] = 3
    data["matchObjectJson"]["winner"] = {"name": "Gen.G", "wins": 1}
    doc = migrate.convert_match("CompletedMatches", data, NOW)
    assert doc["score"] == {"1": 1, "2": 2}
    assert doc["winnerId"] == "2"


def test_bo1_saved_as_zero_zero_is_restored():
    data = old_match("2022-11-05T08:00:00Z", wins=(0, 0), necessary=1)
    data["numberOfGames"] = 1
    data["matchObjectJson"]["winner"] = {"name": "T1", "wins": 0}
    doc = migrate.convert_match("CompletedMatches", data, NOW)
    assert doc["score"] == {"1": 1, "2": 0}
    assert doc["winnerId"] == "1"


def test_score_is_rebuilt_from_game_winners_when_there_is_no_legacy_winner():
    data = old_match("2022-11-05T08:00:00Z", wins=(1, 1), necessary=2)
    data["numberOfGames"] = 3
    data["matchObjectJson"].pop("winner")
    doc = migrate.convert_match("CompletedMatches", data, NOW, game_winners=["1", "2", "2"])
    assert doc["score"] == {"1": 1, "2": 2}
    assert doc["winnerId"] == "2"


def test_live_match_score_is_not_inflated():
    doc = migrate.convert_match("LiveMatches", old_match("2026-11-19T10:00:00Z", wins=(1, 1)), NOW)
    assert doc["score"] == {"1": 1, "2": 1}
