"""Client de GRID: adaptació de les respostes a la forma que fa servir el backend.

Les dades imiten l'estructura real de Series State i Central Data (Worlds 2026).
"""
import copy

import pytest

from leagueoftext import grid, store
from leagueoftext.game import Game
from leagueoftext.match import Match

GEN, BLG = "47558", "356"


def series(series_id="3012986", fmt="Bo5", tournament="Worlds - 2026 (Knockouts: Knockouts)", short="Knockouts",
           start="2026-11-14T19:00:00Z"):
    return {
        "id": series_id,
        "startTimeScheduled": start,
        "format": {"nameShortened": fmt},
        "tournament": {"id": "831635", "name": tournament, "nameShortened": short, "logoUrl": "x",
                       "parent": {"id": "831634", "name": "Worlds - 2026 (Knockouts)"}},
        "teams": [
            {"baseInfo": {"id": GEN, "name": "Gen.G Esports", "nameShortened": "GEN", "logoUrl": "https://cdn/gen"}},
            {"baseInfo": {"id": BLG, "name": "BILIBILI GAMING", "nameShortened": "BLG", "logoUrl": "https://cdn/blg"}},
        ],
    }


def player(pid, name, kills=0, deaths=0, multikills=None, champion="Ahri", first_kill=False):
    return {"id": pid, "name": name, "character": {"name": champion}, "kills": kills, "deaths": deaths,
            "killAssistsGiven": 0, "totalMoneyEarned": 500, "firstKill": first_kill,
            "multikills": [{"numberOfKills": k, "count": c} for k, c in (multikills or {}).items()]}


def team(team_id, name, side, players, won=False, objectives=None, gold=2500, firsts=()):
    return {"id": team_id, "name": name, "side": side, "won": won,
            "kills": sum(p["kills"] for p in players), "deaths": sum(p["deaths"] for p in players),
            "totalMoneyEarned": gold,
            "objectives": [{"type": k, "completionCount": v, "completedFirst": k in firsts}
                           for k, v in (objectives or {}).items()],
            "players": players}


def game_state(number=1, started=True, finished=False, paused=False, clock=0, blue_kills=None, blue_multi=None,
               blue_objectives=None, red_objectives=None, blue_won=False, first_kill=None, red_firsts=(),
               red_kills=None):
    blue_kills = blue_kills or {}
    red_kills = red_kills or {}
    # Els jugadors arriben desordenats: l'adaptador els ordena per id.
    blue = [player(pid, name, kills=blue_kills.get(name, 0), multikills=(blue_multi or {}).get(name),
                   first_kill=name == first_kill)
            for pid, name in (("22090", "Ruler"), ("21335", "Kiin"), ("21233", "Canyon"), ("22132", "Duro"),
                              ("22958", "Chovy"))]
    red = [player(pid, name, kills=red_kills.get(name, 0), first_kill=name == first_kill)
           for pid, name in (("1001", "Bin"), ("1002", "Xun"), ("1003", "Knight"), ("1004", "Viper"), ("1005", "ON"))]
    return {"id": f"uuid-{number}", "sequenceNumber": number, "started": started, "finished": finished,
            "paused": paused, "startedAt": "2026-11-14T19:53:19.800Z", "clock": {"currentSeconds": clock},
            "teams": [team(BLG, "BILIBILI GAMING", "red", red, won=not blue_won and finished, objectives=red_objectives,
                           firsts=red_firsts),
                      team(GEN, "Gen.G Esports", "blue", blue, won=blue_won, objectives=blue_objectives)]}


def state(games, updated="2026-11-14T20:00:00.000Z", started=True, finished=False, series_id="3012986"):
    return {"id": series_id, "started": started, "finished": finished, "valid": True, "updatedAt": updated,
            "teams": [{"id": GEN, "name": "Gen.G Esports", "score": 0, "won": False},
                      {"id": BLG, "name": "BILIBILI GAMING", "score": 0, "won": False}],
            "games": games}


# --------------------------------------------------------------------------- calendari


@pytest.mark.parametrize("fmt, expected", [("Bo1", 1), ("Bo3", 3), ("Bo5", 5), ("", 1), (None, 1)])
def test_best_of(fmt, expected):
    assert grid.best_of({"format": {"nameShortened": fmt}}) == expected


def test_league_for_matches_prefix_and_parent():
    leagues = ("LCK", "LEC", "LCS", "Worlds", "MSI", "First Stand")
    assert grid.league_for({"name": "Worlds - 2026 (Swiss: Swiss)"}, leagues) == "Worlds"
    assert grid.league_for({"name": "Swiss", "parent": {"name": "Worlds - 2026"}}, leagues) == "Worlds"
    assert grid.league_for({"name": "First Stand - 2027 (Groups: Groups)"}, leagues) == "First Stand"
    assert grid.league_for({"name": "LCS Challengers 2027"}, ("LCK",)) is None
    assert grid.league_for({"name": "LCKC - 2027"}, ("LCK",)) is None  # no és la LCK


def test_series_to_event_has_lolesports_shape():
    event = grid.series_to_event(series(), "unstarted", ("Worlds",))
    assert event["state"] == "unstarted"
    assert event["startTime"] == "2026-11-14T19:00:00Z"
    assert event["blockName"] == "Knockouts"
    assert event["league"] == {"id": "831635", "name": "Worlds", "slug": "worlds", "image": ""}
    assert [g["id"] for g in event["match"]["games"]] == [f"3012986-{n}" for n in range(1, 6)]

    match = Match.from_event(event)
    assert match.match_id == "3012986" and match.best_of == 5
    assert match.title() == "GEN vs BLG"
    assert match.next_game_id() == "3012986-1"

    doc = store.match_document_from_event(event)
    assert doc["teams"][0] == {"id": GEN, "code": "GEN", "name": "Gen.G Esports", "image": "https://cdn/gen"}
    assert doc["bestOf"] == 5 and doc["league"]["slug"] == "worlds"


def test_league_slug_matches_app():
    assert grid.league_slug("First Stand") == "first_stand"
    assert grid.league_slug("Worlds") == "worlds"


def test_iter_schedule_events_paginates(monkeypatch):
    from datetime import datetime, timezone

    monkeypatch.setattr(grid, "lol_title_id", lambda: "3")
    pages = {
        None: {"edges": [{"node": series("1", start="2026-11-14T10:00:00Z")}],
               "pageInfo": {"hasNextPage": True, "endCursor": "c1"}},
        "c1": {"edges": [{"node": series("2", start="2026-11-16T10:00:00Z")}],
               "pageInfo": {"hasNextPage": False, "endCursor": None}},
    }
    seen = []

    def fake_graphql(url, query, variables=None):
        seen.append(variables)
        return {"allSeries": pages[variables["after"]]}

    monkeypatch.setattr(grid, "graphql", fake_graphql)
    now = datetime(2026, 11, 15, tzinfo=timezone.utc)
    events = list(grid.iter_schedule_events(now=now))
    assert [(e["id"], e["state"]) for e in events] == [("1", "inProgress"), ("2", "unstarted")]
    assert seen[0]["titleId"] == "3" and seen[0]["from"] == "2026-11-14T00:00:00Z"


# --------------------------------------------------------------------------- jocs


def test_split_game_id():
    assert grid.split_game_id("3012986-2") == ("3012986", 2)
    with pytest.raises(ValueError):
        grid.split_game_id("3012986")


def test_merge_dragons_keeps_known_order():
    first = grid.merge_dragons([], {"cloud": 1})
    assert first == ["cloud"]
    # Un drac nou d'un tipus que va abans a DRAGON_ORDER no reordena els ja coneguts.
    assert grid.merge_dragons(first, {"cloud": 1, "infernal": 1}) == ["cloud", "infernal"]
    assert grid.merge_dragons(["cloud", "infernal"], {"cloud": 1, "infernal": 1, "elder": 1}) == ["cloud", "infernal", "elder"]


def test_dragon_counts_from_objectives():
    objectives = {"slayDragon": 5, "slayOceanDrake": 1, "slayHextechDrake": 2, "slayChemtechDrake": 1,
                  "slayElderDragon": 1, "slayBaron": 1}
    assert grid.dragon_counts(objectives) == {"ocean": 1, "hextech": 2, "chemtech": 1, "elder": 1}


def test_game_to_window_builds_livestats_frame():
    g = game_state(clock=754, blue_kills={"Ruler": 3}, blue_multi={"Ruler": {3: 1}},
                   blue_objectives={"destroyTower": 2, "destroyFortifier": 1, "slayBaron": 1, "slayCloudDrake": 1})
    window = grid.game_to_window(state([g]), g)
    assert window["esportsGameId"] == "3012986-1"
    meta = window["gameMetadata"]["blueTeamMetadata"]
    assert meta["esportsTeamId"] == GEN
    assert [p["summonerName"] for p in meta["participantMetadata"]] == ["Canyon", "Kiin", "Ruler", "Duro", "Chovy"]
    assert window["gameMetadata"]["redTeamMetadata"]["participantMetadata"][0]["participantId"] == 6
    frame = window["frames"][0]
    assert frame["gameState"] == "in_game" and frame["clockSeconds"] == 754
    blue = frame["blueTeam"]
    assert (blue["towers"], blue["inhibitors"], blue["barons"], blue["dragons"]) == (2, 1, 1, ["cloud"])
    ruler = next(p for p in blue["participants"] if p["esportsPlayerId"] == "22090")
    assert ruler["kills"] == 3 and ruler["multikills"] == {"3": 1}


@pytest.mark.parametrize("flags, expected", [({}, "in_game"), ({"paused": True}, "paused"),
                                             ({"finished": True}, "finished")])
def test_game_state_mapping(flags, expected):
    g = game_state(**flags)
    assert grid.game_to_window(state([g]), g)["frames"][0]["gameState"] == expected


def test_get_game_window_none_until_game_starts(monkeypatch):
    monkeypatch.setattr(grid, "get_series_state", lambda sid, max_age=0: state([game_state(1, finished=True)]))
    assert grid.get_game_window("3012986-2") is None  # el joc 2 encara no hi és
    monkeypatch.setattr(grid, "get_series_state", lambda sid, max_age=0: state([game_state(2, started=False)]))
    assert grid.get_game_window("3012986-2") is None
    monkeypatch.setattr(grid, "get_series_state", lambda sid, max_age=0: None)
    assert grid.get_game_window("3012986-1") is None


def test_find_game_winner(monkeypatch):
    games = [game_state(1, finished=True, blue_won=True), game_state(2)]
    monkeypatch.setattr(grid, "get_series_state", lambda sid, max_age=0: state(games))
    assert grid.find_game_winner("3012986", "3012986-1") == GEN
    assert grid.find_game_winner("3012986", "3012986-2") is None  # encara en joc


def test_get_live_event(monkeypatch):
    monkeypatch.setattr(grid, "graphql", lambda url, query, variables=None: {"series": series()})
    monkeypatch.setattr(grid, "get_series_state", lambda sid, max_age=0: state([], started=True))
    event = grid.get_live_event("3012986")
    assert event["state"] == "inProgress" and event["match"]["id"] == "3012986"
    monkeypatch.setattr(grid, "get_series_state", lambda sid, max_age=0: state([], started=False))
    assert grid.get_live_event("3012986") is None
    monkeypatch.setattr(grid, "get_series_state", lambda sid, max_age=0: state([], finished=True))
    assert grid.get_live_event("3012986") is None


def test_state_cache_is_reused_only_when_asked(monkeypatch):
    calls = []
    monkeypatch.setattr(grid, "graphql", lambda url, q, v=None: calls.append(v) or {"seriesState": {"id": v["id"]}})
    grid._state_cache.clear()
    grid.get_series_state("9")
    grid.get_series_state("9", max_age=60)
    grid.get_series_state("9")
    assert len(calls) == 2


def test_graphql_errors_are_raised(monkeypatch):
    class Response:
        status_code = 200

        def json(self):
            return {"errors": [{"message": "Requester forbidden", "extensions": {"errorType": "PERMISSION_DENIED"}}]}

    class Session:
        def post(self, *args, **kwargs):
            return Response()

    monkeypatch.setattr(grid.config, "grid_api_key", lambda: "test")
    monkeypatch.setattr(grid, "_get_session", lambda: Session())
    with pytest.raises(grid.GridError, match="PERMISSION_DENIED"):
        grid.graphql(grid.SERIES_STATE_URL, "query { x }")


def test_missing_grid_key(monkeypatch):
    from leagueoftext import config

    monkeypatch.delenv("GRID_API_KEY", raising=False)
    monkeypatch.delenv("GRID_API_KEY_SECRET_ARN", raising=False)
    config.grid_api_key.cache_clear()
    with pytest.raises(RuntimeError):
        config.grid_api_key()


def test_grid_key_from_secrets_manager(monkeypatch):
    import sys
    from types import SimpleNamespace

    from leagueoftext import config

    calls = []

    class Client:
        def get_secret_value(self, SecretId):
            calls.append(SecretId)
            return {"SecretString": " clau-secreta\n"}

    monkeypatch.setitem(sys.modules, "boto3", SimpleNamespace(client=lambda service: Client()))
    monkeypatch.delenv("GRID_API_KEY", raising=False)
    monkeypatch.setenv("GRID_API_KEY_SECRET_ARN", "arn:aws:secretsmanager:eu:1:secret:grid")
    config.grid_api_key.cache_clear()
    try:
        assert config.grid_api_key() == "clau-secreta"
        assert config.grid_api_key() == "clau-secreta"
        assert calls == ["arn:aws:secretsmanager:eu:1:secret:grid"]  # es llegeix una sola vegada
    finally:
        config.grid_api_key.cache_clear()


def test_invalid_data_source(monkeypatch):
    from leagueoftext import config

    monkeypatch.setenv("DATA_SOURCE", "riot")
    with pytest.raises(RuntimeError):
        config.data_source()


# --------------------------------------------------------------------------- Game amb fotogrames de GRID


def window_at(g, updated, previous=None):
    return grid.game_to_window(state([g], updated=updated), g, previous)


def test_game_with_grid_frames_uses_clock_and_multikill_counts():
    codes = {GEN: "GEN", BLG: "BLG"}
    game = Game.start("3012986", "3012986-1", window_at(game_state(clock=60), "2026-11-14T19:55:00.000Z"), 1, codes)
    game.drain_events()

    # Dues kills en una mateixa lectura, però GRID no diu que sigui una doble: són dues kills soltes.
    g = game_state(clock=300, blue_kills={"Ruler": 2})
    game.update(window_at(g, "2026-11-14T19:59:00.000Z", game.last_frame)["frames"][0])
    # Després, una triple segons els comptadors de GRID.
    g = game_state(clock=600, blue_kills={"Ruler": 5}, blue_multi={"Ruler": {2: 1, 3: 1}})
    game.update(window_at(g, "2026-11-14T20:04:00.000Z", game.last_frame)["frames"][0])

    events = game.drain_events()
    assert [e.text for e in events] == [
        "Ruler got the first blood!\nRuler got a kill.",
        "Ruler got a triple kill.\nRuler is unstoppable!",
    ]
    assert [e.playing_time for e in events] == ["05:00", "10:00"]


def test_game_with_grid_frames_pause_dragons_and_soul():
    codes = {GEN: "GEN", BLG: "BLG"}
    game = Game.start("3012986", "3012986-1", window_at(game_state(clock=60), "2026-11-14T19:55:00.000Z"), 1, codes)
    game.drain_events()

    drakes = {"slayCloudDrake": 2, "slayOceanDrake": 1}
    g = game_state(clock=1200, blue_objectives=drakes)
    game.update(window_at(g, "2026-11-14T20:15:00.000Z", game.last_frame)["frames"][0])
    g = game_state(clock=1250, paused=True, blue_objectives=drakes)
    game.update(window_at(g, "2026-11-14T20:16:00.000Z", game.last_frame)["frames"][0])
    g = game_state(clock=1250, blue_objectives=drakes)
    game.update(window_at(g, "2026-11-14T20:20:00.000Z", game.last_frame)["frames"][0])
    g = game_state(clock=1500, blue_objectives={**drakes, "slayOceanDrake": 2})
    game.update(window_at(g, "2026-11-14T20:25:00.000Z", game.last_frame)["frames"][0])

    texts = [e.text for e in game.drain_events()]
    # Dracs nous en una mateixa lectura: ordre fix (DRAGON_ORDER); els ja coneguts no es mouen.
    assert texts == [
        "GEN killed a ocean dragon.",
        "GEN killed a cloud dragon.",
        "GEN killed a cloud dragon.",
        "The game has been paused!",
        "The game has been unpaused!\nTime paused: 04:00",
        "GEN killed a ocean dragon.",
        "GEN got the ocean soul!",
    ]
    assert game.playing_seconds == 1500


def test_game_with_grid_frames_survives_roundtrip():
    codes = {GEN: "GEN", BLG: "BLG"}
    game = Game.start("3012986", "3012986-1", window_at(game_state(clock=60), "2026-11-14T19:55:00.000Z"), 1, codes)
    restored = Game.from_dict(copy.deepcopy(game.to_dict()))
    g = game_state(clock=300, blue_kills={"Kiin": 1})
    restored.update(window_at(g, "2026-11-14T19:59:00.000Z", restored.last_frame)["frames"][0])
    assert [e.type for e in restored.drain_events()] == ["first_blood"]


# --------------------------------------------------------------------------- inici tardà


def start_late(g):
    return Game.start("3012986", "3012986-1", window_at(g, "2026-11-14T20:01:00.000Z"), 1, {GEN: "GEN", BLG: "BLG"})


def test_late_start_announces_missed_events_with_the_right_teams():
    g = game_state(clock=420, red_kills={"Knight": 1}, first_kill="Knight", blue_kills={"Ruler": 2},
                   red_objectives={"destroyTower": 1}, red_firsts=("destroyTower",), blue_objectives={"destroyTower": 1})
    game = start_late(g)
    events = game.drain_events()
    assert [(e.seq, e.type, e.text) for e in events] == [
        (1, "game_start", "GEN vs BLG game 1 has just started!"),
        (2, "first_tower", "BLG destroyed the first tower!"),  # GRID diu que la primera va ser de BLG
        (3, "tower", "GEN destroyed a tower."),
        (4, "first_blood", "Knight got the first blood!\nKnight got a kill."),
        (5, "kill", "Ruler got 2 kills."),  # no és una doble: GRID no l'ha comptada
    ]
    assert events[0].playing_time == "00:00" and events[1].playing_time == "07:00"

    # Les lectures següents continuen des d'aquí, sense repetir res.
    g = game_state(clock=500, red_kills={"Knight": 1}, first_kill="Knight", blue_kills={"Ruler": 3},
                   red_objectives={"destroyTower": 1}, red_firsts=("destroyTower",), blue_objectives={"destroyTower": 1})
    game.update(window_at(g, "2026-11-14T20:02:00.000Z", game.last_frame)["frames"][0])
    # Ruler porta 3 kills sense morir: la ratxa es compta des de l'inici tardà.
    assert [e.text for e in game.drain_events()] == ["Ruler got a kill.\nRuler is on a killing spree!"]


def test_very_late_start_sends_a_single_summary():
    g = game_state(clock=1500, blue_kills={"Ruler": 6, "Chovy": 3}, first_kill="Ruler",
                   blue_objectives={"destroyTower": 5, "slayCloudDrake": 2, "slayBaron": 1})
    game = start_late(g)
    events = game.drain_events()
    assert len(events) == 1 and events[0].seq == 1 and events[0].type == "game_start"
    assert events[0].text == "GEN vs BLG game 1 is already in progress (25:00)."
    assert "GEN 9 – 0 BLG" in events[0].push_body() and "T 5–0" in events[0].push_body()
    assert game.first_blood and game.first_tower  # no es tornaran a anunciar


def test_late_start_while_paused_or_finished():
    game = start_late(game_state(clock=300, paused=True))
    assert [e.type for e in game.drain_events()] == ["game_start", "pause"]
    assert game.is_paused
    game = start_late(game_state(clock=1800, finished=True, blue_won=True))
    assert game.ended
