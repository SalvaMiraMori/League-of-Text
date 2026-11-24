"""Importació de partits ja jugats des de l'estat final de GRID."""
import pytest
from test_grid import BLG, GEN, game_state, series, state

from leagueoftext import backfill, store


def finished_series(*winners, fmt="Bo3", score=None):
    games = [game_state(n, finished=True, blue_won=(w == GEN), clock=1800 + n) for n, w in enumerate(winners, 1)]
    st = state(games, finished=True)
    wins = {GEN: winners.count(GEN), BLG: winners.count(BLG)}
    for team in st["teams"]:
        team["score"] = (score or wins)[team["id"]]
        team["won"] = team["score"] == max((score or wins).values())
    return series(fmt=fmt), st


def test_builds_completed_match_with_games_and_timeline():
    s, st = finished_series(GEN, BLG, GEN)
    item = backfill.build_completed_match(s, st)
    assert item.warnings == []
    assert item.match.wins() == {GEN: 2, BLG: 1} and item.match.winner == GEN
    assert [g.number for g in item.games] == [1, 2, 3]

    first = item.games[0]
    assert first.game.game_id == "3012986-1" and first.game.winner == GEN and first.game.ended
    assert [(e.seq, e.type, e.playing_time, e.text) for e in first.events] == [
        (1, "game_start", "00:00", "GEN vs BLG game 1 has just started!"),
        (2, "game_end", "30:01", "GEN wins the game!"),
    ]
    assert first.events[0].stats["blue"]["kills"] == 0  # l'inici, a zero
    assert "Knockouts" in backfill.summary(item) and "GEN  2 - 1  BLG" in backfill.summary(item)


def test_bo1_has_no_game_number():
    s, st = finished_series(BLG, fmt="Bo1")
    item = backfill.build_completed_match(s, st)
    assert item.games[0].events[0].text == "GEN vs BLG has just started!"
    assert item.match.winner == BLG


def test_unfinished_series_is_skipped():
    s, st = finished_series(GEN)
    st["finished"] = False
    assert backfill.build_completed_match(s, st) is None
    assert backfill.build_completed_match(s, None) is None


def test_warns_when_grid_score_disagrees():
    s, st = finished_series(GEN, GEN, score={GEN: 2, BLG: 1})
    item = backfill.build_completed_match(s, st)
    assert any("no coincideix" in w for w in item.warnings)


def test_game_without_winner_is_reported():
    s, st = finished_series(GEN, GEN)
    for team in st["games"][1]["teams"]:
        team["won"] = False
    item = backfill.build_completed_match(s, st)
    assert [g.number for g in item.games] == [1]
    assert any("no té guanyador" in w for w in item.warnings)


def test_find_duplicates_matches_same_day_and_teams():
    s, st = finished_series(GEN, GEN)
    item = backfill.build_completed_match(s, st)
    existing = [
        ("3012986", {"startTime": "2026-11-14T19:00:00Z", "teams": [{"code": "GEN"}, {"code": "BLG"}]}),
        ("1131", {"startTime": "2026-11-14T08:00:00Z", "teams": [{"code": "BLG"}, {"code": "gen"}]}),
        ("1132", {"startTime": "2026-11-13T08:00:00Z", "teams": [{"code": "BLG"}, {"code": "GEN"}]}),
        ("1133", {"startTime": "2026-11-14T08:00:00Z", "teams": [{"code": "T1"}, {"code": "GEN"}]}),
    ]
    assert backfill.find_duplicates([item], existing) == [("1131", "3012986")]


class FakeBatch:
    def __init__(self, writes):
        self.writes = writes

    def set(self, ref, data, merge=False):
        self.writes.setdefault(ref.path, {}).update(data)

    def commit(self):
        pass


class FakeRef:
    def __init__(self, path):
        self.path = path

    def collection(self, name):
        return FakeRef(f"{self.path}/{name}")

    def document(self, doc_id):
        return FakeRef(f"{self.path}/{doc_id}")


class FakeDb:
    def __init__(self):
        self.writes = {}

    def batch(self):
        return FakeBatch(self.writes)


def test_save_writes_the_same_documents_as_the_live_backend(monkeypatch):
    db = FakeDb()
    monkeypatch.setattr(store, "db", lambda: db)
    monkeypatch.setattr(store, "match_ref", lambda match_id: FakeRef(f"Matches/{match_id}"))
    monkeypatch.setattr(store, "game_ref", lambda m, g: FakeRef(f"Matches/{m}/Games/{g}"))

    s, st = finished_series(GEN, BLG, GEN)
    item = backfill.build_completed_match(s, st)
    backfill.save_completed_match(item)
    backfill.save_completed_match(item)  # es pot repetir: mateixos documents

    match = db.writes["Matches/3012986"]
    assert match["state"] == "completed" and match["winnerId"] == GEN
    assert match["score"] == {GEN: 2, BLG: 1} and match["league"]["name"] == "Worlds"
    game = db.writes["Matches/3012986/Games/3012986-2"]
    assert game["number"] == 2 and game["state"] == "completed" and game["winnerId"] == BLG
    assert game["playingSeconds"] == 1802 and game["blueTeam"] == "GEN"
    notifications = sorted(p for p in db.writes if p.startswith("Matches/3012986/Games/3012986-2/Notifications/"))
    assert [p.rsplit("/", 1)[1] for p in notifications] == ["00001", "00002"]
    assert len([p for p in db.writes if "/Games/" in p and "/Notifications/" not in p]) == 3


@pytest.mark.parametrize("value, expected", [("2026-11-14T19:00:00Z", "2026-11-14"), ("", "")])
def test_day(value, expected):
    assert backfill._day(value) == expected
