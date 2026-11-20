"""Encadena les sis Lambdes amb Firestore, FCM, EventBridge i l'API simulats."""
from types import SimpleNamespace

import pytest
from conftest import BLUE_ID, RED_ID, frame, team_frame

from leagueoftext import lolesports, messaging, scheduling, store
from leagueoftext.game import Game
from leagueoftext.handlers import (
    game_is_live,
    get_scheduled_matches,
    match_is_live,
    match_is_live_scheduler,
    update_game_state,
    update_match_state,
)
from leagueoftext.match import Match

CONTEXT = SimpleNamespace(invoked_function_arn="arn:aws:lambda:eu-west-3:123456789012:function:Test")

SCHEDULE_EVENT = {
    "id": "m1",
    "type": "match",
    "state": "unstarted",
    "startTime": "2099-01-01T10:00:00Z",
    "blockName": "Final",
    "league": {"name": "Worlds", "slug": "worlds", "image": "http://x/worlds.png"},
    "match": {
        "id": "m1",
        "strategy": {"count": 1},
        "teams": [{"id": BLUE_ID, "code": "T1", "name": "T1"}, {"id": RED_ID, "code": "GEN", "name": "Gen.G"}],
        "games": [{"id": "g1", "number": 1}],
    },
}


class FakeStore:
    def __init__(self):
        self.matches, self.games, self.notifications = {}, {}, {}

    def get_match(self, match_id):
        return self.matches.get(match_id)

    def upsert_scheduled_match(self, event):
        doc = store.match_document_from_event(event)
        self.matches[doc["esportsMatchId"]] = {**doc, "state": "scheduled"}

    def mark_match_live(self, event, match):
        self.matches[match.match_id] = {**store.match_document_from_event(event), "state": "live", "engine": match.to_dict()}

    def load_match(self, match_id):
        data = self.matches.get(match_id)
        return Match.from_dict(data["engine"]) if data and "engine" in data else None

    def save_match(self, match, completed=False):
        doc = self.matches[match.match_id]
        doc.update(engine=match.to_dict(), winnerId=match.winner, score=match.wins())
        if completed:
            doc["state"] = "completed"

    def delete_match(self, match_id):
        self.matches.pop(match_id, None)

    def load_game(self, match_id, game_id):
        data = self.games.get(game_id)
        return Game.from_dict(data) if data else None

    def save_game(self, game, events):
        self.games[game.game_id] = game.to_dict()
        for event in events:
            self.notifications[event.doc_id] = event.to_firestore()


@pytest.fixture
def env(monkeypatch):
    monkeypatch.setenv("DATA_SOURCE", "lolesports")
    fake = FakeStore()
    for name in ("get_match", "upsert_scheduled_match", "mark_match_live", "load_match", "save_match",
                 "delete_match", "load_game", "save_game"):
        monkeypatch.setattr(store, name, getattr(fake, name))

    pushes, calls = [], []
    monkeypatch.setattr(messaging, "send", lambda match_id, title, body, **kw: pushes.append(body) or True)
    monkeypatch.setattr(messaging, "send_game_events", lambda events, m, g: pushes.extend(e.text for e in events))

    polling = {}
    monkeypatch.setattr(scheduling.Scheduler, "schedule_once", lambda self, rule, when, target, payload: calls.append(("once", rule, payload)))
    monkeypatch.setattr(scheduling.Scheduler, "delete_rule", lambda self, rule: calls.append(("delete", rule)))
    monkeypatch.setattr(scheduling.Scheduler, "add_polling_target", lambda self, logical, tid, payload: polling.__setitem__(tid, payload))
    monkeypatch.setattr(scheduling.Scheduler, "remove_polling_target", lambda self, logical, tid: polling.pop(tid, None))
    monkeypatch.setattr(scheduling.Scheduler, "invoke_async", lambda self, logical, payload: calls.append(("invoke", logical, payload)))
    return SimpleNamespace(store=fake, pushes=pushes, calls=calls, polling=polling)


def test_full_match_flow(env, monkeypatch, window):
    # 1. GetScheduledMatches desa el partit i programa la regla one-shot.
    monkeypatch.setattr(lolesports, "iter_schedule_events", lambda direction="newer": iter([SCHEDULE_EVENT]))
    monkeypatch.setattr(get_scheduled_matches, "utcnow", lambda: lolesports.parse_timestamp("2098-12-25T00:00:00Z"))
    result = get_scheduled_matches.lambda_handler({}, CONTEXT)
    assert result["scheduled"] == 1
    assert env.store.matches["m1"]["state"] == "scheduled"
    assert env.store.matches["m1"]["league"]["image"] == "https://x/worlds.png"
    assert env.calls[-1][0] == "once"
    # Una segona execució sense canvis no torna a programar res.
    assert get_scheduled_matches.lambda_handler({}, CONTEXT)["skipped"] == 1

    # 2. MatchIsLiveScheduler -> objectiu a MatchIsLive.
    match_is_live_scheduler.lambda_handler({"matchId": "m1"}, CONTEXT)
    assert "MatchIsLive-m1" in env.polling

    # 3. MatchIsLive: encara no ha començat; després sí.
    monkeypatch.setattr(lolesports, "get_live_events", lambda: [])
    assert match_is_live.lambda_handler({"matchId": "m1"}, CONTEXT)["live"] is False
    monkeypatch.setattr(lolesports, "get_live_events", lambda: [{**SCHEDULE_EVENT, "state": "inProgress"}])
    assert match_is_live.lambda_handler({"matchId": "m1"}, CONTEXT)["live"] is True
    assert "MatchIsLive-m1" not in env.polling
    assert env.calls[-1] == ("invoke", "UpdateMatchState", {"matchId": "m1"})

    # 4. UpdateMatchState programa el primer joc.
    update_match_state.lambda_handler({"matchId": "m1"}, CONTEXT)
    assert env.polling["GameIsLive-g1"]["gameNumber"] == 0

    # 5. GameIsLive crea el joc quan hi ha livestats.
    monkeypatch.setattr(lolesports, "get_game_window", lambda game_id, starting_time=None: window)
    game_is_live.lambda_handler(env.polling["GameIsLive-g1"], CONTEXT)
    assert "GameIsLive-g1" not in env.polling
    payload = env.polling["UpdateGameState-g1"]
    assert payload["date"] == "2026-11-14T08:00:10.000Z"
    assert env.pushes[-1] == "T1 vs GEN has just started!"

    # 6. UpdateGameState processa finestres fins que el joc acaba.
    windows = {
        "2026-11-14T08:00:10.000Z": [frame("2026-11-14T08:00:15.000Z", blue=team_frame(kills={1: 1}))],
        "2026-11-14T08:00:20.000Z": [frame("2026-11-14T08:00:25.000Z", state="finished",
                                           blue=team_frame(kills={1: 1}, towers=11, inhibitors=3))],
    }

    def fake_window(game_id, starting_time=None):
        frames = windows.get(lolesports.format_window_time(starting_time))
        return {"frames": frames} if frames else None

    monkeypatch.setattr(lolesports, "get_game_window", fake_window)
    monkeypatch.setattr(lolesports, "find_game_winner", lambda m, g, wins: None)
    update_game_state.lambda_handler(payload, CONTEXT)
    assert env.store.games["g1"]["ended"] and env.store.games["g1"]["winner"] is None
    assert "UpdateGameState-g1" in env.polling  # espera que l'API digui el guanyador

    monkeypatch.setattr(lolesports, "find_game_winner", lambda m, g, wins: BLUE_ID)
    update_game_state.lambda_handler(env.polling["UpdateGameState-g1"], CONTEXT)
    assert env.store.games["g1"]["winner"] == BLUE_ID
    assert "UpdateGameState-g1" not in env.polling
    assert env.calls[-1] == ("invoke", "UpdateMatchState", {"matchId": "m1", "gameId": "g1"})

    # 7. UpdateMatchState tanca el partit (Bo1).
    update_match_state.lambda_handler({"matchId": "m1", "gameId": "g1"}, CONTEXT)
    assert env.store.matches["m1"]["state"] == "completed"
    assert env.store.matches["m1"]["winnerId"] == BLUE_ID
    # Una invocació duplicada no suma dues victòries.
    update_match_state.lambda_handler({"matchId": "m1", "gameId": "g1"}, CONTEXT)
    assert env.store.matches["m1"]["score"][BLUE_ID] == 1

    assert "T1 Player1 got the first blood!\nT1 Player1 got a kill." in env.pushes
    assert env.pushes[-1] == "T1 wins the game!"
    ids = sorted(env.store.notifications)
    assert ids == [f"{n:05d}" for n in range(1, len(ids) + 1)]  # ids únics i consecutius
    assert env.store.notifications[ids[-1]]["type"] == "game_end"


def test_postponed_match_is_dropped(env, monkeypatch):
    env.store.matches["m1"] = {"state": "scheduled", "startTime": "2020-01-01T00:00:00Z"}
    env.polling["MatchIsLive-m1"] = {}
    monkeypatch.setattr(lolesports, "get_live_events", lambda: [])
    match_is_live.lambda_handler({"matchId": "m1", "startTime": "2020-01-01T00:00:00Z"}, CONTEXT)
    assert "m1" not in env.store.matches and "MatchIsLive-m1" not in env.polling


def test_scheduler_builds_arns():
    scheduler = scheduling.Scheduler(CONTEXT.invoked_function_arn)
    assert scheduler.function_arn("GameIsLive") == "arn:aws:lambda:eu-west-3:123456789012:function:GameIsLive"


def test_full_match_flow_with_grid(env, monkeypatch):
    """El mateix recorregut amb GRID: Bo3 que GEN guanya 2-0, amb l'API de GRID simulada."""
    from test_grid import BLG, GEN, game_state, series, state

    from leagueoftext import grid

    monkeypatch.setenv("DATA_SOURCE", "grid")
    monkeypatch.setattr(grid, "lol_title_id", lambda: "3")
    monkeypatch.setattr(update_game_state, "sleep", lambda seconds: None)
    bo3 = series("777", fmt="Bo3", tournament="Worlds - 2099 (Swiss: Swiss)", short="Swiss", start="2099-01-01T10:00:00Z")

    def fake_graphql(url, query, variables=None):
        if "allSeries" in query:
            return {"allSeries": {"edges": [{"node": bo3}], "pageInfo": {"hasNextPage": False}}}
        return {"series": bo3}

    current = {"state": None}
    monkeypatch.setattr(grid, "graphql", fake_graphql)
    monkeypatch.setattr(grid, "get_series_state", lambda sid, max_age=0: current["state"])

    # 1-2. Calendari i MatchIsLiveScheduler.
    monkeypatch.setattr(get_scheduled_matches, "utcnow", lambda: lolesports.parse_timestamp("2098-12-25T00:00:00Z"))
    monkeypatch.setattr(grid, "iter_schedule_events",
                        lambda direction="newer", _orig=grid.iter_schedule_events:
                        _orig(now=lolesports.parse_timestamp("2098-12-25T00:00:00Z")))
    assert get_scheduled_matches.lambda_handler({}, CONTEXT)["scheduled"] == 1
    doc = env.store.matches["777"]
    assert doc["league"]["name"] == "Worlds" and doc["blockName"] == "Swiss" and doc["bestOf"] == 3
    match_is_live_scheduler.lambda_handler({"matchId": "777"}, CONTEXT)

    # 3. MatchIsLive: GRID encara no té estat; després la sèrie ha començat.
    assert match_is_live.lambda_handler({"matchId": "777"}, CONTEXT)["live"] is False
    current["state"] = state([], series_id="777")
    assert match_is_live.lambda_handler({"matchId": "777"}, CONTEXT)["live"] is True

    # 4. UpdateMatchState programa el joc 1 (id sintètic).
    update_match_state.lambda_handler({"matchId": "777"}, CONTEXT)
    assert env.pushes[-1] == "The match has just started!"
    payload = env.polling["GameIsLive-777-1"]

    # 5. GameIsLive: espera que el joc 1 aparegui a Series State.
    assert game_is_live.lambda_handler(payload, CONTEXT)["started"] is False
    current["state"] = state([game_state(1, clock=30)], updated="2099-01-01T10:10:00.000Z", series_id="777")
    assert game_is_live.lambda_handler(payload, CONTEXT)["started"] is True
    assert env.pushes[-1] == "GEN vs BLG game 1 has just started!"

    # 6. UpdateGameState: primera sang i final del joc (4 lectures per execució com a màxim).
    current["state"] = state([game_state(1, clock=400, blue_kills={"Chovy": 1})],
                             updated="2099-01-01T10:17:00.000Z", series_id="777")
    result = update_game_state.lambda_handler(env.polling["UpdateGameState-777-1"], CONTEXT)
    assert result["windows"] == grid.POLLS_PER_RUN and result["winner"] is None
    assert "Chovy got the first blood!\nChovy got a kill." in env.pushes

    current["state"] = state([game_state(1, clock=1900, finished=True, blue_won=True, blue_kills={"Chovy": 1},
                                         blue_objectives={"destroyTower": 9, "destroyNexus": 1})],
                             updated="2099-01-01T10:42:00.000Z", series_id="777")
    result = update_game_state.lambda_handler(env.polling["UpdateGameState-777-1"], CONTEXT)
    assert result["windows"] == 1 and result["winner"] == GEN  # s'atura en veure el final
    assert env.pushes[-1] == "GEN wins the game!"
    update_match_state.lambda_handler({"matchId": "777", "gameId": "777-1"}, CONTEXT)
    assert env.pushes[-1] == "GEN  1 - 0  BLG"
    assert "GameIsLive-777-2" in env.polling

    # 7. Joc 2 i final del partit.
    g1 = game_state(1, clock=1900, finished=True, blue_won=True)
    current["state"] = state([g1, game_state(2, clock=20)], updated="2099-01-01T11:00:00.000Z", series_id="777")
    game_is_live.lambda_handler(env.polling["GameIsLive-777-2"], CONTEXT)
    current["state"] = state([g1, game_state(2, clock=1700, finished=True, blue_won=True)],
                             updated="2099-01-01T11:30:00.000Z", series_id="777")
    update_game_state.lambda_handler(env.polling["UpdateGameState-777-2"], CONTEXT)
    update_match_state.lambda_handler({"matchId": "777", "gameId": "777-2"}, CONTEXT)
    assert env.store.matches["777"]["state"] == "completed"
    assert env.store.matches["777"]["winnerId"] == GEN
    assert env.pushes[-1] == "GEN  2 - 0  BLG\nGEN WINS THE MATCH!"
    assert BLG in env.store.matches["777"]["score"]
