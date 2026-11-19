from datetime import datetime, timezone

import pytest

from leagueoftext import lolesports
from leagueoftext.match import Match

EVENT = {
    "id": "m1",
    "startTime": "2026-11-14T08:00:00Z",
    "state": "inProgress",
    "league": {"name": "Worlds", "slug": "worlds"},
    "match": {
        "id": "m1",
        "strategy": {"type": "bestOf", "count": 5},
        "teams": [
            {"id": "1", "code": "GEN", "name": "Gen.G", "image": "http://img/gen.png"},
            {"id": "2", "code": "BLG", "name": "Bilibili Gaming", "image": "https://img/blg.png"},
        ],
        "games": [{"id": f"g{n}", "number": n} for n in (3, 1, 2, 5, 4)],
    },
}


def test_from_event():
    match = Match.from_event(EVENT)
    assert match.best_of == 5 and match.wins_needed == 3
    assert match.game_ids == ["g1", "g2", "g3", "g4", "g5"]
    assert match.teams[0].image == "https://img/gen.png"
    assert match.title() == "GEN vs BLG"


def test_series_flow_and_duplicates():
    match = Match.from_event(EVENT)
    assert match.next_game_id() == "g1" and match.next_game_number() == 1
    assert match.record_game_winner("1", "g1")
    assert not match.record_game_winner("1", "g1")  # invocació duplicada
    match.record_game_winner("2", "g2")
    match.record_game_winner("1", "g3")
    assert match.score_text() == "GEN  2 - 1  BLG"
    assert not match.is_finished
    match.record_game_winner("1", "g4")
    assert match.is_finished and match.winner == "1"
    assert match.winner_text().endswith("GEN WINS THE MATCH!")


def test_bo2_can_draw():
    event = {**EVENT, "match": {**EVENT["match"], "strategy": {"count": 2}, "games": [{"id": "a", "number": 1}, {"id": "b", "number": 2}]}}
    match = Match.from_event(event)
    match.record_game_winner("1", "a")
    match.record_game_winner("2", "b")
    assert match.is_finished and match.winner is None
    assert "draw" in match.winner_text()


def test_bo1_game_number_is_zero():
    event = {**EVENT, "match": {**EVENT["match"], "strategy": {"count": 1}, "games": [{"id": "a", "number": 1}]}}
    assert Match.from_event(event).next_game_number() == 0


def test_roundtrip():
    match = Match.from_event(EVENT)
    match.record_game_winner("2", "g1")
    assert Match.from_dict(match.to_dict()) == match


def test_unknown_team_raises():
    with pytest.raises(ValueError):
        Match.from_event(EVENT).record_game_winner("999")


@pytest.mark.parametrize(
    "raw, expected",
    [
        ("2026-11-14T08:12:30.123Z", datetime(2026, 11, 14, 8, 12, 30, 123000, tzinfo=timezone.utc)),
        ("2026-11-14T08:12:30Z", datetime(2026, 11, 14, 8, 12, 30, tzinfo=timezone.utc)),
        ("2026-11-14T08:12:30.1Z", datetime(2026, 11, 14, 8, 12, 30, 100000, tzinfo=timezone.utc)),
    ],
)
def test_parse_timestamp(raw, expected):
    assert lolesports.parse_timestamp(raw) == expected


def test_next_window_start():
    start = lolesports.next_window_start("2026-11-14T08:12:37.456Z")
    assert lolesports.format_window_time(start) == "2026-11-14T08:12:40.000Z"


def test_find_game_winner_by_game_wins(monkeypatch):
    monkeypatch.setattr(
        lolesports,
        "get_event_details",
        lambda _id: {"match": {"teams": [{"id": "1", "result": {"gameWins": 1}}, {"id": "2", "result": {"gameWins": 2}}],
                               "games": [{"id": "g3", "state": "completed", "teams": [{"id": "1"}, {"id": "2"}]}]}},
    )
    assert lolesports.find_game_winner("m1", "g3", {"1": 1, "2": 1}) == "2"
    assert lolesports.find_game_winner("m1", "g3", {"1": 1, "2": 2}) is None


def test_missing_api_key(monkeypatch):
    monkeypatch.delenv("LOLESPORTS_API_KEY", raising=False)
    from leagueoftext import config

    with pytest.raises(RuntimeError):
        config.lolesports_api_key()
