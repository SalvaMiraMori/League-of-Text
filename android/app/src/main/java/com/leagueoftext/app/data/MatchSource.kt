package com.leagueoftext.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Origen dels partits que fan servir les pantalles. N'hi ha dues implementacions:
 * [MatchRepository] (Firestore) i [MockMatchRepository] (dades inventades per a captures i
 * demostracions, activades amb la propietat de Gradle `leagueoftext.mockData`).
 */
interface MatchSource {
    val scheduled: StateFlow<Feed<List<Match>>>
    val live: StateFlow<Feed<List<Match>>>
    val completed: StateFlow<Feed<List<Match>>>

    suspend fun refreshLists()
    suspend fun refreshMatch(matchId: String, gameId: String?)

    fun match(matchId: String): Flow<Match?>
    fun games(matchId: String): Flow<List<Game>>

    /** Esdeveniments d'un joc, del més recent al més antic. */
    fun events(matchId: String, gameId: String): Flow<List<GameEvent>>
}
