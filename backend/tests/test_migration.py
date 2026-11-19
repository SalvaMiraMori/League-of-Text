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
