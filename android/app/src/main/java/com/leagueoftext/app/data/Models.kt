package com.leagueoftext.app.data

import java.time.Instant

enum class MatchState(val firestoreValue: String) {
    SCHEDULED("scheduled"),
    LIVE("live"),
    COMPLETED("completed");

    companion object {
        fun from(value: String?): MatchState =
            entries.firstOrNull { it.firestoreValue == value } ?: SCHEDULED
    }
}

data class League(
    val id: String,
    val name: String,
    val slug: String,
    val image: String,
) {
    /** Clau per agrupar i filtrar (alguns partits antics no tenen id de lliga). */
    val key: String get() = slug.ifBlank { name }
}

data class Team(
    val id: String,
    val code: String,
    val name: String,
    val image: String,
) {
    val isTbd: Boolean get() = id.isBlank() || code.equals("TBD", ignoreCase = true)

    companion object {
        val TBD = Team(id = "", code = "TBD", name = "TBD", image = "")
    }
}

data class Match(
    val id: String,
    val state: MatchState,
    val startTime: Instant,
    val bestOf: Int,
    val blockName: String,
    val league: League?,
    val teams: List<Team>,
    val score: Map<String, Int>,
    val winnerId: String?,
) {
    val team1: Team get() = teams.getOrElse(0) { Team.TBD }
    val team2: Team get() = teams.getOrElse(1) { Team.TBD }
    val hasScore: Boolean get() = score.values.any { it > 0 } || state != MatchState.SCHEDULED
    fun wins(team: Team): Int = score[team.id] ?: 0
    val title: String get() = "${team1.code} vs ${team2.code}"
}

enum class GameState { UNSTARTED, IN_PROGRESS, COMPLETED;

    companion object {
        fun from(value: String?): GameState = when (value) {
            "inProgress" -> IN_PROGRESS
            "completed", "finished" -> COMPLETED
            else -> UNSTARTED
        }
    }
}

data class TeamStats(
    val kills: Int = 0,
    val gold: Int = 0,
    val towers: Int = 0,
    val inhibitors: Int = 0,
    val barons: Int = 0,
    val dragons: Int = 0,
) {
    val goldLabel: String get() = String.format(java.util.Locale.ROOT, "%.1fk", gold / 1000.0)
}

data class GameStats(val blue: TeamStats, val red: TeamStats)

data class Game(
    val id: String,
    val number: Int,
    val state: GameState,
    val winnerId: String?,
    val blueTeam: String,
    val redTeam: String,
    val blueTeamId: String?,
    val redTeamId: String?,
    val playingSeconds: Int,
    val paused: Boolean,
    val stats: GameStats?,
)

enum class EventSide { BLUE, RED }

/** Tipus d'esdeveniment que genera el backend (camp ``type``). */
enum class EventType {
    GAME_START, GAME_END, KILL, FIRST_BLOOD, MULTIKILL, TOWER, FIRST_TOWER, INHIBITOR,
    BARON, DRAGON, ELDER, SOUL, PAUSE, UNPAUSE, OTHER;

    companion object {
        fun from(value: String?, text: String): EventType = when (value) {
            "game_start" -> GAME_START
            "game_end" -> GAME_END
            "kill" -> KILL
            "first_blood" -> FIRST_BLOOD
            "multikill" -> MULTIKILL
            "tower" -> TOWER
            "first_tower" -> FIRST_TOWER
            "inhibitor" -> INHIBITOR
            "baron" -> BARON
            "dragon" -> DRAGON
            "elder" -> ELDER
            "soul" -> SOUL
            "pause" -> PAUSE
            "unpause" -> UNPAUSE
            else -> guessFromText(text)
        }

        /** Les notificacions antigues no tenen ``type``: es dedueix del text. */
        private fun guessFromText(text: String): EventType {
            val t = text.lowercase()
            return when {
                "has just started" in t -> GAME_START
                "wins the game" in t -> GAME_END
                "first blood" in t -> FIRST_BLOOD
                "double kill" in t || "triple kill" in t || "quadra kill" in t || "pentakill" in t -> MULTIKILL
                "got a kill" in t -> KILL
                "first tower" in t -> FIRST_TOWER
                "tower" in t -> TOWER
                "inhibitor" in t -> INHIBITOR
                "baron" in t -> BARON
                "soul" in t -> SOUL
                "elder" in t -> ELDER
                "dragon" in t -> DRAGON
                "unpaused" in t -> UNPAUSE
                "paused" in t -> PAUSE
                else -> OTHER
            }
        }
    }
}

data class GameEvent(
    val id: String,
    val order: Int,
    val type: EventType,
    val text: String,
    val playingTime: String,
    val side: EventSide?,
    val blueTeam: String,
    val redTeam: String,
    val stats: GameStats?,
)
