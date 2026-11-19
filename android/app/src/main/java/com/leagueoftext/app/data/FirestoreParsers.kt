package com.leagueoftext.app.data

import com.google.firebase.firestore.DocumentSnapshot
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Conversió de documents de Firestore a models.
 *
 * Es llegeix camp a camp (sense `toObject`) i amb valors per defecte, perquè una dada
 * inesperada no faci petar l'app (la versió anterior feia `as String` a tot arreu).
 * També s'accepta el format antic de les notificacions (blueTeamKills, redTeamGold...).
 */
internal object FirestoreParsers {

    fun match(doc: DocumentSnapshot): Match? {
        val startTime = parseInstant(doc.getString("startTime")) ?: return null
        val teams = (doc.get("teams") as? List<*>)
            ?.mapNotNull { item -> (item as? Map<*, *>)?.let { team(it) } }
            ?: emptyList()
        val score = (doc.get("score") as? Map<*, *>)
            ?.entries
            ?.mapNotNull { entry ->
                val key = entry.key as? String ?: return@mapNotNull null
                key to ((entry.value as? Number)?.toInt() ?: 0)
            }
            ?.toMap()
            ?: emptyMap()
        val bestOf = (doc.getLong("bestOf") ?: doc.getLong("numberOfGames") ?: 1L).toInt()
        return Match(
            id = doc.getString("esportsMatchId") ?: doc.id,
            state = MatchState.from(doc.getString("state")),
            startTime = startTime,
            bestOf = bestOf,
            blockName = doc.getString("blockName").orEmpty(),
            league = (doc.get("league") as? Map<*, *>)?.let { league(it) },
            teams = teams,
            score = score,
            winnerId = doc.getString("winnerId"),
        )
    }

    private fun team(map: Map<*, *>): Team {
        val name = map.string("name")
        if (name.isBlank()) return Team.TBD
        return Team(
            id = map.string("id"),
            code = map.string("code").ifBlank { name.take(3).uppercase() },
            name = name,
            image = map.string("image").replace("http:", "https:"),
        )
    }

    private fun league(map: Map<*, *>): League? {
        val name = map.string("name")
        if (name.isBlank()) return null
        return League(
            id = map.string("id"),
            name = name,
            slug = map.string("slug"),
            image = map.string("image").replace("http:", "https:"),
        )
    }

    fun game(doc: DocumentSnapshot): Game = Game(
        id = doc.getString("esportsGameId") ?: doc.id,
        number = (doc.getLong("number") ?: 0L).toInt(),
        state = GameState.from(doc.getString("state")),
        winnerId = doc.getString("winnerId"),
        blueTeam = doc.getString("blueTeam").orEmpty(),
        redTeam = doc.getString("redTeam").orEmpty(),
        blueTeamId = doc.getString("blueTeamId"),
        redTeamId = doc.getString("redTeamId"),
        playingSeconds = (doc.get("playingSeconds") as? Number)?.toInt() ?: 0,
        paused = doc.getBoolean("paused") ?: false,
        stats = (doc.get("stats") as? Map<*, *>)?.let { gameStats(it) },
    )

    fun event(doc: DocumentSnapshot): GameEvent {
        val text = doc.getString("text").orEmpty().trim()
        val playingTime = doc.getString("playingTime") ?: doc.id
        val seq = (doc.get("seq") as? Number)?.toInt()
        val stats = (doc.get("stats") as? Map<*, *>)?.let { gameStats(it) } ?: legacyStats(doc)
        return GameEvent(
            id = doc.id,
            // Les notificacions antigues no tenen seq: s'ordenen pel temps de joc.
            order = seq ?: clockToSeconds(playingTime),
            type = EventType.from(doc.getString("type"), text),
            text = text,
            playingTime = playingTime,
            side = when (doc.getString("side")) {
                "blue" -> EventSide.BLUE
                "red" -> EventSide.RED
                else -> null
            },
            blueTeam = doc.getString("blueSideTeam").orEmpty(),
            redTeam = doc.getString("redSideTeam").orEmpty(),
            stats = stats,
        )
    }

    private fun gameStats(map: Map<*, *>): GameStats? {
        val blue = (map["blue"] as? Map<*, *>)?.let { teamStats(it) } ?: return null
        val red = (map["red"] as? Map<*, *>)?.let { teamStats(it) } ?: return null
        return GameStats(blue, red)
    }

    private fun teamStats(map: Map<*, *>) = TeamStats(
        kills = map.int("kills"),
        gold = map.int("gold"),
        towers = map.int("towers"),
        inhibitors = map.int("inhibitors"),
        barons = map.int("barons"),
        dragons = map.int("dragons"),
    )

    private fun legacyStats(doc: DocumentSnapshot): GameStats? {
        fun num(field: String) = doc.getString(field)?.trim()?.toDoubleOrNull()
        val blueKills = num("blueTeamKills") ?: return null
        return GameStats(
            blue = TeamStats(
                kills = blueKills.toInt(),
                gold = ((num("blueTeamGold") ?: 0.0) * 1000).toInt(),
                towers = num("blueTeamTowers")?.toInt() ?: 0,
                dragons = num("blueTeamDragons")?.toInt() ?: 0,
            ),
            red = TeamStats(
                kills = num("redTeamKills")?.toInt() ?: 0,
                gold = ((num("redTeamGold") ?: 0.0) * 1000).toInt(),
                towers = num("redTeamTowers")?.toInt() ?: 0,
                dragons = num("redTeamDragons")?.toInt() ?: 0,
            ),
        )
    }

    fun parseInstant(value: String?): Instant? = try {
        value?.let { Instant.parse(it) }
    } catch (e: DateTimeParseException) {
        null
    }

    /** "12:34" -> 754, "1:02:34" -> 3754. */
    fun clockToSeconds(clock: String): Int =
        clock.split(":").mapNotNull { it.trim().toIntOrNull() }.fold(0) { acc, part -> acc * 60 + part }

    private fun Map<*, *>.string(key: String): String = (this[key] as? String).orEmpty()
    private fun Map<*, *>.int(key: String): Int = (this[key] as? Number)?.toInt() ?: 0
}
