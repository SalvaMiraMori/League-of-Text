"""Estat d'un partit (sèrie al millor de N jocs)."""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Optional


@dataclass
class MatchTeam:
    id: str
    code: str
    name: str
    image: str = ""
    wins: int = 0

    def to_dict(self) -> dict:
        return {"id": self.id, "code": self.code, "name": self.name, "image": self.image, "wins": self.wins}

    @classmethod
    def from_dict(cls, data: dict) -> "MatchTeam":
        return cls(
            id=str(data["id"]),
            code=data.get("code") or data.get("name", "TBD"),
            name=data.get("name", "TBD"),
            image=data.get("image", ""),
            wins=int(data.get("wins", 0)),
        )


@dataclass
class Match:
    match_id: str
    best_of: int
    teams: list[MatchTeam]
    game_ids: list[str] = field(default_factory=list)
    winner: Optional[str] = None
    counted_games: list[str] = field(default_factory=list)

    # ------------------------------------------------------------------ construcció

    @classmethod
    def from_event(cls, event: dict) -> "Match":
        """Crea el partit a partir d'un esdeveniment de ``schedule`` o ``live``."""
        details = event.get("match") or {}
        match_id = str(details.get("id") or event["id"])
        strategy = details.get("strategy") or {}
        best_of = int(strategy.get("count") or len(event.get("games") or details.get("games") or []) or 1)
        teams = [team_from_api(t) for t in (details.get("teams") or event.get("teams") or [])]
        games = event.get("games") or details.get("games") or []
        game_ids = [str(g["id"]) for g in sorted(games, key=lambda g: g.get("number", 0))]
        return cls(match_id=match_id, best_of=best_of, teams=teams, game_ids=game_ids)

    @classmethod
    def from_dict(cls, data: dict) -> "Match":
        return cls(
            match_id=data["esportsMatchId"],
            best_of=int(data["bestOf"]),
            teams=[MatchTeam.from_dict(t) for t in data["teams"]],
            game_ids=list(data.get("gameIds", [])),
            winner=data.get("winner"),
            counted_games=list(data.get("countedGames", [])),
        )

    def to_dict(self) -> dict:
        return {
            "esportsMatchId": self.match_id,
            "bestOf": self.best_of,
            "teams": [t.to_dict() for t in self.teams],
            "gameIds": self.game_ids,
            "winner": self.winner,
            "countedGames": self.counted_games,
        }

    # ------------------------------------------------------------------ estat

    @property
    def wins_needed(self) -> int:
        # La versió anterior només contemplava Bo1/Bo3/Bo5.
        return self.best_of // 2 + 1

    @property
    def games_played(self) -> int:
        return sum(t.wins for t in self.teams)

    @property
    def is_finished(self) -> bool:
        return self.winner is not None or self.games_played >= self.best_of

    def team(self, team_id: str) -> Optional[MatchTeam]:
        return next((t for t in self.teams if t.id == team_id), None)

    def team_codes(self) -> dict[str, str]:
        return {t.id: t.code for t in self.teams}

    def wins(self) -> dict[str, int]:
        return {t.id: t.wins for t in self.teams}

    def next_game_id(self) -> Optional[str]:
        index = self.games_played
        return self.game_ids[index] if index < len(self.game_ids) else None

    def next_game_number(self) -> int:
        """Número de joc per als avisos (0 en un Bo1, perquè no es mostri "game 1")."""
        return 0 if self.best_of == 1 else self.games_played + 1

    def record_game_winner(self, team_id: str, game_id: str | None = None) -> bool:
        """Suma la victòria d'un joc. Retorna ``False`` si aquest joc ja s'havia comptat
        (p. ex. si la Lambda s'ha invocat dues vegades)."""
        if game_id is not None and game_id in self.counted_games:
            return False
        team = self.team(team_id)
        if team is None:
            raise ValueError(f"L'equip {team_id} no juga el partit {self.match_id}")
        team.wins += 1
        if game_id is not None:
            self.counted_games.append(game_id)
        if team.wins >= self.wins_needed:
            self.winner = team.id
        return True

    # ------------------------------------------------------------------ textos

    def title(self) -> str:
        return " vs ".join(t.code for t in self.teams) if self.teams else self.match_id

    def score_text(self) -> str:
        if len(self.teams) != 2:
            return ""
        a, b = self.teams
        return f"{a.code}  {a.wins} - {b.wins}  {b.code}"

    def winner_text(self) -> str:
        team = self.team(self.winner) if self.winner else None
        if team is None:
            return f"{self.score_text()}\nThe match ended in a draw."
        return f"{self.score_text()}\n{team.code} WINS THE MATCH!"


def team_from_api(team: dict) -> MatchTeam:
    return MatchTeam(
        id=str(team.get("id") or team.get("code") or "TBD"),
        code=team.get("code") or "TBD",
        name=team.get("name") or "TBD",
        image=(team.get("image") or "").replace("http://", "https://"),
    )
