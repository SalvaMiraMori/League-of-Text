"""Seguiment d'un joc a partir de les instantànies de ``livestats``.

La classe :class:`Game` no parla ni amb Firestore ni amb FCM: genera objectes
:class:`GameEvent` que el *handler* de la Lambda desa i envia. Així la lògica
es pot provar amb tests unitaris.
"""
from __future__ import annotations

import logging
from dataclasses import dataclass, field
from datetime import datetime
from typing import Optional

from .lolesports import format_timestamp, parse_timestamp

log = logging.getLogger(__name__)

BLUE = "blue"
RED = "red"
SIDES = (BLUE, RED)

MULTIKILL_NAMES = {1: "kill", 2: "double kill", 3: "triple kill", 4: "quadra kill", 5: "PENTAKILL"}
SPREE_MESSAGES = {
    3: "is on a killing spree!",
    4: "is on a rampage!",
    5: "is unstoppable!",
    6: "is dominating!",
    7: "is godlike!",
}
LEGENDARY = "is legendary!"
MULTIKILL_WINDOW_S = 10
PENTAKILL_WINDOW_S = 30
#: Si en veure el joc per primera vegada ja havien passat més esdeveniments que aquests, no
#: s'envien un per un: l'avís d'inici diu que el joc ja està en marxa (i porta les estadístiques).
CATCH_UP_MAX_EVENTS = 5


def format_clock(seconds: float) -> str:
    """``754`` -> ``"12:34"``; ``3754`` -> ``"1:02:34"``."""
    total = max(0, int(seconds))
    hours, rest = divmod(total, 3600)
    minutes, secs = divmod(rest, 60)
    if hours:
        return f"{hours}:{minutes:02d}:{secs:02d}"
    return f"{minutes:02d}:{secs:02d}"


def _multikill_from_counts(previous: dict, current: dict) -> Optional[int]:
    """Multikill segons els comptadors de GRID (``{"2": dobles, "3": triples…}``).

    Retorna la multikill més alta que ha augmentat (1 si només hi ha kills soltes), o ``None``
    si el fotograma no porta comptadors (feed de LoL Esports: es calcula amb finestres de temps).
    """
    if "multikills" not in current:
        return None
    before = previous.get("multikills") or {}
    increased = [
        int(size) for size, count in (current.get("multikills") or {}).items() if int(count) > int(before.get(size, 0))
    ]
    return min(5, max(increased)) if increased else 1


# --------------------------------------------------------------------------- models


@dataclass
class TeamStats:
    kills: int = 0
    gold: int = 0
    towers: int = 0
    inhibitors: int = 0
    barons: int = 0
    dragons: list[str] = field(default_factory=list)

    @classmethod
    def from_frame(cls, team_frame: dict) -> "TeamStats":
        participants = team_frame.get("participants", [])
        kills = team_frame.get("totalKills")
        if kills is None:
            kills = sum(p.get("kills", 0) for p in participants)
        return cls(
            kills=int(kills),
            gold=int(team_frame.get("totalGold", 0)),
            towers=int(team_frame.get("towers", 0)),
            inhibitors=int(team_frame.get("inhibitors", 0)),
            barons=int(team_frame.get("barons", 0)),
            dragons=list(team_frame.get("dragons", [])),
        )

    def to_dict(self) -> dict:
        return {
            "kills": self.kills,
            "gold": self.gold,
            "towers": self.towers,
            "inhibitors": self.inhibitors,
            "barons": self.barons,
            "dragons": len(self.dragons),
            "dragonTypes": list(self.dragons),
        }


@dataclass
class GameEvent:
    """Un esdeveniment del joc: es desa a Firestore i s'envia com a notificació push."""

    seq: int
    type: str
    text: str
    playing_seconds: int
    side: Optional[str]
    blue_team: str
    red_team: str
    stats: dict

    @property
    def playing_time(self) -> str:
        return format_clock(self.playing_seconds)

    @property
    def doc_id(self) -> str:
        # Identificador únic i ordenable. La versió anterior feia servir el temps de joc com a id
        # i dos esdeveniments en el mateix segon se sobreescrivien.
        return f"{self.seq:05d}"

    def to_firestore(self) -> dict:
        return {
            "seq": self.seq,
            "type": self.type,
            "text": self.text,
            "playingTime": self.playing_time,
            "playingSeconds": self.playing_seconds,
            "side": self.side,
            "blueSideTeam": self.blue_team,
            "redSideTeam": self.red_team,
            "stats": self.stats,
        }

    def push_title(self) -> str:
        return f"{self.blue_team} vs {self.red_team} · {self.playing_time}"

    def push_body(self) -> str:
        blue, red = self.stats[BLUE], self.stats[RED]
        return (
            f"{self.text}\n"
            f"{self.blue_team} {blue['kills']} – {red['kills']} {self.red_team}  ·  "
            f"{blue['gold'] / 1000:.1f}k – {red['gold'] / 1000:.1f}k  ·  "
            f"T {blue['towers']}–{red['towers']}  ·  D {blue['dragons']}–{red['dragons']}"
        )


@dataclass
class Participant:
    participant_id: int
    summoner_name: str
    esports_player_id: str = ""
    champion_id: str = ""
    role: str = ""
    last_kill_at: Optional[datetime] = None
    multikill: int = 0
    killing_spree: int = 0

    @classmethod
    def from_metadata(cls, meta: dict) -> "Participant":
        return cls(
            participant_id=int(meta["participantId"]),
            summoner_name=meta.get("summonerName", f"Player {meta['participantId']}"),
            esports_player_id=str(meta.get("esportsPlayerId", "")),
            champion_id=meta.get("championId", ""),
            role=meta.get("role", ""),
        )

    @classmethod
    def from_dict(cls, data: dict) -> "Participant":
        last = data.get("lastKillAt")
        return cls(
            participant_id=int(data["participantId"]),
            summoner_name=data["summonerName"],
            esports_player_id=data.get("esportsPlayerId", ""),
            champion_id=data.get("championId", ""),
            role=data.get("role", ""),
            last_kill_at=parse_timestamp(last) if last else None,
            multikill=int(data.get("multikill", 0)),
            killing_spree=int(data.get("killingSpree", 0)),
        )

    def to_dict(self) -> dict:
        return {
            "participantId": self.participant_id,
            "summonerName": self.summoner_name,
            "esportsPlayerId": self.esports_player_id,
            "championId": self.champion_id,
            "role": self.role,
            "lastKillAt": self.last_kill_at.isoformat() if self.last_kill_at else None,
            "multikill": self.multikill,
            "killingSpree": self.killing_spree,
        }

    def register_counted_kills(self, count: int, multikill: int, at: datetime) -> None:
        """Com :meth:`register_kills`, però amb la multikill que ja indica la font (GRID)."""
        self.multikill = multikill
        self.last_kill_at = at
        self.killing_spree += count

    def register_kills(self, count: int, at: datetime) -> int:
        """Actualitza el comptador de multikill i retorna el valor resultant (1..5)."""
        window = PENTAKILL_WINDOW_S if self.multikill == 4 else MULTIKILL_WINDOW_S
        chained = (
            self.last_kill_at is not None
            and 0 < self.multikill < 5
            and (at - self.last_kill_at).total_seconds() <= window
        )
        self.multikill = min(5, self.multikill + count) if chained else min(5, count)
        self.last_kill_at = at
        self.killing_spree += count
        return self.multikill


@dataclass
class Team:
    esports_team_id: str
    name: str
    participants: dict[int, Participant]

    @classmethod
    def from_metadata(cls, meta: dict, codes: dict[str, str] | None = None) -> "Team":
        team_id = str(meta["esportsTeamId"])
        participants = {
            int(p["participantId"]): Participant.from_metadata(p) for p in meta.get("participantMetadata", [])
        }
        name = (codes or {}).get(team_id)
        if not name:
            # Els noms d'invocador del feed comencen pel codi de l'equip ("T1 Faker").
            first = next(iter(participants.values()), None)
            name = first.summoner_name.split()[0] if first else team_id
        return cls(team_id, name, participants)

    @classmethod
    def from_dict(cls, data: dict) -> "Team":
        participants = {int(k): Participant.from_dict(v) for k, v in data["participants"].items()}
        return cls(str(data["esportsTeamId"]), data["name"], participants)

    def to_dict(self) -> dict:
        return {
            "esportsTeamId": self.esports_team_id,
            "name": self.name,
            "participants": {str(k): v.to_dict() for k, v in self.participants.items()},
        }


# --------------------------------------------------------------------------- game


class Game:
    def __init__(
        self,
        match_id: str,
        game_id: str,
        game_number: int,
        teams: dict[str, Team],
        starting_time: datetime,
        last_frame: dict,
    ):
        self.match_id = match_id
        self.game_id = game_id
        self.game_number = game_number
        self.teams = teams
        self.starting_time = starting_time
        self.last_frame = last_frame
        self.paused_seconds = 0.0
        self.paused_since: Optional[datetime] = None
        self.playing_seconds = 0.0
        self.first_blood = False
        self.first_tower = False
        self.ended = False
        self.winner: Optional[str] = None
        self.winner_attempts = 0
        self.seq = 0
        self.pending: list[GameEvent] = []

    # ------------------------------------------------------------------ creació / persistència

    @classmethod
    def start(
        cls,
        match_id: str,
        game_id: str,
        window: dict,
        game_number: int,
        team_codes: dict[str, str] | None = None,
    ) -> "Game":
        """Crea el joc a partir de la primera finestra de ``livestats`` i genera l'avís d'inici.

        ``game_number`` és 0 per a partits al millor d'1 (no es mostra "game 1").
        """
        metadata = window["gameMetadata"]
        first_frame = window["frames"][0]
        teams = {
            BLUE: Team.from_metadata(metadata["blueTeamMetadata"], team_codes),
            RED: Team.from_metadata(metadata["redTeamMetadata"], team_codes),
        }
        game = cls(match_id, game_id, game_number, teams, parse_timestamp(first_frame["rfc460Timestamp"]), first_frame)
        number = f"game {game_number} " if game_number > 0 else ""
        title = f"{teams[BLUE].name} vs {teams[RED].name} {number}"

        game._catch_up(first_frame)
        missed = game.drain_events()
        game.seq = 0
        if len(missed) > CATCH_UP_MAX_EVENTS:
            game._emit("game_start", f"{title}is already in progress ({format_clock(game.playing_seconds)}).")
            return game
        playing = game.playing_seconds
        game.playing_seconds = 0
        game._emit("game_start", f"{title}has just started!")
        game.playing_seconds = playing
        for event in missed:
            game.seq += 1
            event.seq = game.seq
            game.pending.append(event)
        return game

    @classmethod
    def from_dict(cls, data: dict) -> "Game":
        game = cls(
            match_id=data["esportsMatchId"],
            game_id=data["esportsGameId"],
            game_number=int(data["gameNumber"]),
            teams={side: Team.from_dict(data["teams"][side]) for side in SIDES},
            starting_time=parse_timestamp(data["startingTime"]),
            last_frame=data["lastFrame"],
        )
        game.paused_seconds = float(data.get("pausedSeconds", 0))
        game.paused_since = parse_timestamp(data["pausedSince"]) if data.get("pausedSince") else None
        game.playing_seconds = float(data.get("playingSeconds", 0))
        game.first_blood = bool(data.get("firstBlood"))
        game.first_tower = bool(data.get("firstTower"))
        game.ended = bool(data.get("ended"))
        game.winner = data.get("winner")
        game.winner_attempts = int(data.get("winnerAttempts", 0))
        game.seq = int(data.get("seq", 0))
        return game

    def to_dict(self) -> dict:
        return {
            "esportsMatchId": self.match_id,
            "esportsGameId": self.game_id,
            "gameNumber": self.game_number,
            "teams": {side: team.to_dict() for side, team in self.teams.items()},
            "startingTime": format_timestamp(self.starting_time),
            "lastFrame": self.last_frame,
            "pausedSeconds": self.paused_seconds,
            "pausedSince": self.paused_since.isoformat() if self.paused_since else None,
            "playingSeconds": self.playing_seconds,
            "firstBlood": self.first_blood,
            "firstTower": self.first_tower,
            "ended": self.ended,
            "winner": self.winner,
            "winnerAttempts": self.winner_attempts,
            "seq": self.seq,
        }

    @property
    def is_paused(self) -> bool:
        return self.paused_since is not None

    def team_ids(self) -> dict[str, str]:
        return {side: team.esports_team_id for side, team in self.teams.items()}

    def side_of(self, team_id: str) -> Optional[str]:
        for side, team in self.teams.items():
            if team.esports_team_id == team_id:
                return side
        return None

    # ------------------------------------------------------------------ actualització

    def update(self, frame: dict) -> None:
        """Processa una instantània nova i acumula els esdeveniments a ``self.pending``."""
        if self.ended or frame == self.last_frame:
            return
        state = frame.get("gameState")
        now = parse_timestamp(frame["rfc460Timestamp"])
        clock = frame.get("clockSeconds")  # GRID dona el temps de joc; livestats no
        last = self.last_frame.get("rfc460Timestamp")
        if last and now <= parse_timestamp(last):
            # Les finestres del feed es poden solapar: un fotograma antic donaria diferències falses.
            return

        if state == "paused":
            if not self.is_paused:
                self.paused_since = now
                self._update_clock(now, clock)
                self.last_frame = frame
                self._emit("pause", "The game has been paused!")
            else:
                self.last_frame = frame
            return

        if self.is_paused:
            pause_length = now - self.paused_since
            self.paused_seconds += pause_length.total_seconds()
            self.paused_since = None
            self._update_clock(now, clock)
            self._emit("unpause", f"The game has been unpaused!\nTime paused: {format_clock(pause_length.total_seconds())}")

        previous = self.last_frame
        self.last_frame = frame
        self._update_clock(now, clock)

        if state in ("in_game", "finished") and previous.get("blueTeam") and frame.get("blueTeam"):
            for side in SIDES:
                key = f"{side}Team"
                self._team_events(side, TeamStats.from_frame(previous[key]), TeamStats.from_frame(frame[key]))
            for side in SIDES:
                key = f"{side}Team"
                self._participant_events(side, previous[key]["participants"], frame[key]["participants"], now)

        if state == "finished":
            self.ended = True

    def _update_clock(self, now: datetime, clock: float | None = None) -> None:
        if clock is not None:
            self.playing_seconds = max(0.0, float(clock))
            return
        self.playing_seconds = max(0.0, (now - self.starting_time).total_seconds() - self.paused_seconds)

    def _team_events(self, side: str, before: TeamStats, after: TeamStats) -> None:
        name = self.teams[side].name
        for _ in range(max(0, after.towers - before.towers)):
            if not self.first_tower:
                self.first_tower = True
                self._emit("first_tower", f"{name} destroyed the first tower!", side)
            else:
                self._emit("tower", f"{name} destroyed a tower.", side)
        for _ in range(max(0, after.inhibitors - before.inhibitors)):
            self._emit("inhibitor", f"{name} destroyed an inhibitor!", side)
        for _ in range(max(0, after.barons - before.barons)):
            self._emit("baron", f"{name} killed Baron Nashor!", side)
        for index in range(len(before.dragons), len(after.dragons)):
            dragon = after.dragons[index]
            if dragon == "elder":
                self._emit("elder", f"{name} killed the Elder Dragon!", side)
                continue
            self._emit("dragon", f"{name} killed a {dragon} dragon.", side)
            if sum(1 for d in after.dragons[: index + 1] if d != "elder") == 4:
                self._emit("soul", f"{name} got the {dragon} soul!", side)

    def _participant_events(self, side: str, before: list[dict], after: list[dict], now: datetime) -> None:
        previous_by_id = {int(p["participantId"]): p for p in before}
        for current in after:
            pid = int(current["participantId"])
            prev = previous_by_id.get(pid)
            participant = self.teams[side].participants.get(pid)
            if prev is None or participant is None:
                continue
            new_kills = int(current.get("kills", 0)) - int(prev.get("kills", 0))
            new_deaths = int(current.get("deaths", 0)) - int(prev.get("deaths", 0))

            if new_kills > 0:
                lines = []
                event_type = "kill"
                if not self.first_blood:
                    self.first_blood = True
                    event_type = "first_blood"
                    lines.append(f"{participant.summoner_name} got the first blood!")
                multikill = _multikill_from_counts(prev, current)
                if multikill is None:
                    multikill = participant.register_kills(new_kills, now)
                else:
                    participant.register_counted_kills(new_kills, multikill, now)
                if multikill > 1:
                    event_type = "multikill"
                lines.append(f"{participant.summoner_name} got a {MULTIKILL_NAMES[multikill]}.")
                spree = participant.killing_spree
                if spree >= 3:
                    lines.append(f"{participant.summoner_name} {SPREE_MESSAGES.get(spree, LEGENDARY)}")
                self._emit(event_type, "\n".join(lines), side)

            if new_deaths > 0:
                participant.killing_spree = 0
                participant.multikill = 0

    # ------------------------------------------------------------------ inici tardà

    def _catch_up(self, frame: dict) -> None:
        """Esdeveniments que ja havien passat quan es veu el joc per primera vegada.

        Si ``GameIsLive`` troba el joc quan ja fa estona que ha començat (p. ex. una Lambda
        endarrerida), el primer fotograma ja porta kills, torres o dracs. Es comparen amb un
        joc a zero perquè no es perdin. Com que no se sap l'ordre en què han passat:

        * les kills s'anuncien totals per jugador ("got 3 kills"), sense ratxes;
        * la primera sang només s'atribueix si el fotograma diu qui l'ha feta (``firstKill``, GRID);
        * les multikills només surten si la font les compta (GRID).
        """
        if not frame.get("blueTeam") or not frame.get("redTeam"):
            return
        self._update_clock(parse_timestamp(frame["rfc460Timestamp"]), frame.get("clockSeconds"))
        towers = sum(int(frame[f"{side}Team"].get("towers", 0)) for side in SIDES)
        first_tower_side = next((side for side in SIDES if frame[f"{side}Team"].get("firstTower")), None)
        if towers and first_tower_side is None:
            self.first_tower = True  # no se sap qui l'ha feta: cap torre no s'anuncia com la primera
        for side in sorted(SIDES, key=lambda side: side != first_tower_side):
            self._team_events(side, TeamStats(), TeamStats.from_frame(frame[f"{side}Team"]))

        participants = [(side, p) for side in SIDES for p in frame[f"{side}Team"].get("participants", [])]
        kills = sum(int(p.get("kills", 0)) for _, p in participants)
        first = next((int(p["participantId"]) for _, p in participants if p.get("firstKill")), None)
        # Primer qui va fer la primera sang, perquè el missatge surti abans.
        participants.sort(key=lambda sp: int(sp[1]["participantId"]) != first)
        for side, current in participants:
            participant = self.teams[side].participants.get(int(current["participantId"]))
            count = int(current.get("kills", 0))
            if participant is None:
                continue
            participant.killing_spree = count if int(current.get("deaths", 0)) == 0 else 0
            if count <= 0:
                continue
            name = participant.summoner_name
            lines, event_type = [], "kill"
            if participant.participant_id == first and not self.first_blood:
                self.first_blood = True
                event_type = "first_blood"
                lines.append(f"{name} got the first blood!")
            lines.append(f"{name} got a kill." if count == 1 else f"{name} got {count} kills.")
            best = max((int(k) for k, n in (current.get("multikills") or {}).items() if int(n) > 0), default=1)
            if best > 1:
                event_type = "multikill" if event_type == "kill" else event_type
                lines.append(f"{name} got a {MULTIKILL_NAMES[min(best, 5)]}.")
            self._emit(event_type, "\n".join(lines), side)
        if kills:
            self.first_blood = True  # les kills següents ja no poden ser la primera sang

        if frame.get("gameState") == "paused":
            self.paused_since = parse_timestamp(frame["rfc460Timestamp"])
            self._emit("pause", "The game has been paused!")
        elif frame.get("gameState") == "finished":
            self.ended = True

    # ------------------------------------------------------------------ final

    def finish(self, winner_team_id: str) -> None:
        self.ended = True
        self.winner = winner_team_id
        side = self.side_of(winner_team_id)
        name = self.teams[side].name if side else winner_team_id
        self._emit("game_end", f"{name} wins the game!", side)

    def guess_winner(self) -> Optional[str]:
        """Últim recurs si l'API no diu qui ha guanyat: l'equip amb més inhibidors, torres i kills."""
        frame = self.last_frame
        if not frame.get("blueTeam") or not frame.get("redTeam"):
            return None
        score = {}
        for side in SIDES:
            stats = TeamStats.from_frame(frame[f"{side}Team"])
            score[side] = (stats.inhibitors, stats.towers, stats.kills, stats.gold)
        if score[BLUE] == score[RED]:
            return None
        best = BLUE if score[BLUE] > score[RED] else RED
        return self.teams[best].esports_team_id

    # ------------------------------------------------------------------ esdeveniments

    def current_stats(self) -> dict:
        stats = {}
        for side in SIDES:
            team_frame = self.last_frame.get(f"{side}Team")
            stats[side] = (TeamStats.from_frame(team_frame) if team_frame else TeamStats()).to_dict()
        return stats

    def _emit(self, event_type: str, text: str, side: Optional[str] = None) -> None:
        self.seq += 1
        event = GameEvent(
            seq=self.seq,
            type=event_type,
            text=text,
            playing_seconds=int(self.playing_seconds),
            side=side,
            blue_team=self.teams[BLUE].name,
            red_team=self.teams[RED].name,
            stats=self.current_stats(),
        )
        log.info("[%s] %s %s", self.game_id, event.playing_time, text.replace("\n", " | "))
        self.pending.append(event)

    def drain_events(self) -> list[GameEvent]:
        events, self.pending = self.pending, []
        return events

