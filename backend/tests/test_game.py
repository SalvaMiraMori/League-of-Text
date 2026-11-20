import json

from conftest import BLUE_ID, RED_ID

from leagueoftext.game import Game, format_clock


def start(window):
    game = Game.start("m1", "g1", window, 2, {BLUE_ID: "T1", RED_ID: "GEN"})
    return game, game.drain_events()


def texts(events):
    return [e.text for e in events]


def test_start_notification_uses_team_codes(window):
    game, events = start(window)
    assert texts(events) == ["T1 vs GEN game 2 has just started!"]
    assert events[0].type == "game_start"
    assert events[0].playing_time == "00:00"


def test_bo1_has_no_game_number(window):
    game = Game.start("m1", "g1", window, 0)
    assert game.drain_events()[0].text == "T1 vs GEN has just started!"


def test_first_blood_double_kill_and_spree(window, make_frame, make_team):
    game, _ = start(window)
    game.update(make_frame("2026-11-14T08:05:00.000Z", blue=make_team(kills={1: 1})))
    game.update(make_frame("2026-11-14T08:05:05.000Z", blue=make_team(kills={1: 2})))
    game.update(make_frame("2026-11-14T08:05:09.000Z", blue=make_team(kills={1: 3})))
    events = game.drain_events()
    assert texts(events) == [
        "T1 Player1 got the first blood!\nT1 Player1 got a kill.",
        "T1 Player1 got a double kill.",
        "T1 Player1 got a triple kill.\nT1 Player1 is on a killing spree!",
    ]
    assert [e.type for e in events] == ["first_blood", "multikill", "multikill"]
    assert events[0].playing_time == "05:00"
    assert events[0].side == "blue"
    assert events[2].stats["blue"]["kills"] == 3


def test_multikill_window_expires_and_new_streak_starts(window, make_frame, make_team):
    game, _ = start(window)
    game.update(make_frame("2026-11-14T08:05:00.000Z", blue=make_team(kills={1: 1})))
    game.update(make_frame("2026-11-14T08:05:30.000Z", blue=make_team(kills={1: 2})))
    # El segon kill comença una nova ratxa: el tercer, 5 s després, és un doble kill.
    game.update(make_frame("2026-11-14T08:05:35.000Z", blue=make_team(kills={1: 3})))
    assert texts(game.drain_events())[1:] == [
        "T1 Player1 got a kill.",
        "T1 Player1 got a double kill.\nT1 Player1 is on a killing spree!",
    ]


def test_pentakill_uses_30_second_window(window, make_frame, make_team):
    game, _ = start(window)
    for second, kills in ((0, 1), (3, 2), (6, 3), (9, 4), (35, 5)):
        game.update(make_frame(f"2026-11-14T08:10:{second:02d}.000Z", blue=make_team(kills={2: kills})))
    assert "T1 Player2 got a PENTAKILL." in game.drain_events()[-1].text


def test_death_resets_spree(window, make_frame, make_team):
    game, _ = start(window)
    game.update(make_frame("2026-11-14T08:05:00.000Z", blue=make_team(kills={1: 1})))
    game.update(make_frame("2026-11-14T08:05:20.000Z", blue=make_team(kills={1: 2})))
    game.update(make_frame("2026-11-14T08:06:00.000Z", blue=make_team(kills={1: 2}, deaths={1: 1})))
    game.update(make_frame("2026-11-14T08:07:00.000Z", blue=make_team(kills={1: 3}, deaths={1: 1})))
    assert game.teams["blue"].participants[1].killing_spree == 1


def test_objectives(window, make_frame, make_team):
    game, _ = start(window)
    game.update(make_frame("2026-11-14T08:14:00.000Z", red=make_team(offset=5, towers=1)))
    game.update(make_frame("2026-11-14T08:15:00.000Z", red=make_team(offset=5, towers=1),
                           blue=make_team(towers=1, dragons=["ocean"])))
    game.update(make_frame("2026-11-14T08:25:00.000Z", red=make_team(offset=5, towers=1),
                           blue=make_team(towers=1, barons=1, inhibitors=1,
                                          dragons=["ocean", "fire", "ocean", "ocean"])))
    game.update(make_frame("2026-11-14T08:35:00.000Z", red=make_team(offset=5, towers=1),
                           blue=make_team(towers=1, barons=1, inhibitors=1,
                                          dragons=["ocean", "fire", "ocean", "ocean", "elder"])))
    assert texts(game.drain_events()) == [
        "GEN destroyed the first tower!",
        "T1 destroyed a tower.",
        "T1 killed a ocean dragon.",
        "T1 destroyed an inhibitor!",
        "T1 killed Baron Nashor!",
        "T1 killed a fire dragon.",
        "T1 killed a ocean dragon.",
        "T1 killed a ocean dragon.",
        "T1 got the ocean soul!",
        "T1 killed the Elder Dragon!",
    ]


def test_pause_is_notified_once_and_clock_excludes_pause(window, make_frame):
    game, _ = start(window)
    game.update(make_frame("2026-11-14T08:10:00.000Z"))
    game.update(make_frame("2026-11-14T08:10:10.000Z", state="paused"))
    game.update(make_frame("2026-11-14T08:11:00.000Z", state="paused"))
    game.update(make_frame("2026-11-14T08:12:10.000Z", state="paused"))
    game.update(make_frame("2026-11-14T08:13:10.000Z"))
    events = game.drain_events()
    assert texts(events) == ["The game has been paused!", "The game has been unpaused!\nTime paused: 03:00"]
    assert events[0].playing_time == "10:10"
    assert events[1].playing_time == "10:10"
    assert game.playing_seconds == 610


def test_old_frames_are_ignored(window, make_frame, make_team):
    game, _ = start(window)
    game.update(make_frame("2026-11-14T08:05:00.000Z", blue=make_team(kills={1: 1})))
    game.update(make_frame("2026-11-14T08:04:50.000Z"))
    game.update(make_frame("2026-11-14T08:05:10.000Z", blue=make_team(kills={1: 1})))
    assert len(game.drain_events()) == 1


def test_finish_and_unique_ids(window, make_frame):
    game, start_events = start(window)
    game.update(make_frame("2026-11-14T08:30:00.000Z", state="finished"))
    assert game.ended and game.winner is None
    game.finish(RED_ID)
    events = start_events + game.drain_events()
    assert events[-1].text == "GEN wins the game!"
    assert len({e.doc_id for e in events}) == len(events)


def test_guess_winner(window, make_frame, make_team):
    game, _ = start(window)
    game.update(make_frame("2026-11-14T08:30:00.000Z", state="finished",
                           red=make_team(offset=5, towers=9, inhibitors=2)))
    assert game.guess_winner() == RED_ID


def test_roundtrip_is_json_serializable(window, make_frame, make_team):
    game, _ = start(window)
    game.update(make_frame("2026-11-14T08:05:00.000Z", blue=make_team(kills={1: 1})))
    game.update(make_frame("2026-11-14T08:06:00.000Z", state="paused"))
    data = json.loads(json.dumps(game.to_dict()))
    restored = Game.from_dict(data)
    assert restored.is_paused
    assert restored.first_blood
    assert restored.teams["blue"].participants[1].multikill == 1
    restored.update(make_frame("2026-11-14T08:07:00.000Z", blue=make_team(kills={1: 2})))
    assert restored.drain_events()[0].text.startswith("The game has been unpaused!")


def test_push_texts(window):
    _, events = start(window)
    assert events[0].push_title() == "T1 vs GEN · 00:00"
    assert "T1 0 – 0 GEN" in events[0].push_body()


def test_format_clock():
    assert format_clock(0) == "00:00"
    assert format_clock(754) == "12:34"
    assert format_clock(3754) == "1:02:34"


def test_late_start_with_livestats_does_not_guess_first_blood_or_multikills(window, make_frame, make_team, deepcopy):
    late = deepcopy(window)
    late["frames"] = [make_frame("2026-11-14T08:06:00.000Z", blue=make_team(kills={1: 3}, towers=1),
                                 red=make_team(kills={6: 1}, deaths={6: 1}, offset=5))]
    game = Game.start("m1", "g1", late, 2, {BLUE_ID: "T1", RED_ID: "GEN"})
    assert texts(game.drain_events()) == [
        "T1 vs GEN game 2 has just started!",
        "T1 destroyed a tower.",  # el feed no diu qui va fer la primera torre
        "T1 Player1 got 3 kills.",  # ni l'ordre de les kills: res de primera sang ni de triple
        "GEN Player6 got a kill.",
    ]
    assert game.first_blood and game.first_tower
    assert game.teams["blue"].participants[1].killing_spree == 3
    assert game.teams["red"].participants[6].killing_spree == 0
