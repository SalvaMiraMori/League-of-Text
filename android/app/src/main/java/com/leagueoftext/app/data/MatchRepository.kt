package com.leagueoftext.app.data

import android.util.Log
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/** Dades d'una llista que es carrega en temps real. */
data class Feed<T>(
    val data: T,
    val loading: Boolean = false,
    val error: Throwable? = null,
)

/**
 * Accés de només lectura als partits.
 *
 * Cada llista (programats, en directe, acabats) és **un sol** listener de Firestore,
 * compartit per totes les pantalles i aturat 5 s després que ningú l'observi. La versió
 * anterior afegia listeners nous cada vegada que arribava un canvi (i no els treia mai),
 * i en tornava a demanar els jocs i les notificacions de tots els partits.
 *
 * La memòria cau de Firestore fa que les dades es mostrin també sense connexió.
 */
class MatchRepository(db: FirebaseFirestore, scope: CoroutineScope) : MatchSource {

    private val matches = db.collection(MATCHES)

    private val scheduledQuery = matches.whereEqualTo(STATE, MatchState.SCHEDULED.firestoreValue)
        .orderBy(START_TIME, Query.Direction.ASCENDING)
        .limit(150)
    private val liveQuery = matches.whereEqualTo(STATE, MatchState.LIVE.firestoreValue)
    private val completedQuery = matches.whereEqualTo(STATE, MatchState.COMPLETED.firestoreValue)
        .orderBy(START_TIME, Query.Direction.DESCENDING)
        .limit(60)

    /** Cada increment torna a crear els listeners de les llistes (p. ex. si estaven esperant un reintent). */
    private val restartFeeds = MutableStateFlow(0)

    override val scheduled: StateFlow<Feed<List<Match>>> = feed(scheduledQuery, scope)
    override val live: StateFlow<Feed<List<Match>>> = feed(liveQuery, scope)
    override val completed: StateFlow<Feed<List<Match>>> = feed(completedQuery, scope)

    /**
     * Refresc manual de les llistes (estirar cap avall). Els listeners ja reben els canvis en temps
     * real, però aquí es força una lectura al servidor: actualitza la memòria cau (i, per tant, els
     * listeners actius) i, si alguna llista estava en error, en reinicia el listener sense esperar
     * el següent reintent.
     */
    override suspend fun refreshLists() {
        if (listOf(scheduled, live, completed).any { it.value.error != null }) restartFeeds.update { it + 1 }
        fetchFromServer(
            { scheduledQuery.get(Source.SERVER).await() },
            { liveQuery.get(Source.SERVER).await() },
            { completedQuery.get(Source.SERVER).await() },
        )
    }

    /** Refresc manual del detall d'un partit: el partit, els seus jocs i els esdeveniments del joc visible. */
    override suspend fun refreshMatch(matchId: String, gameId: String?) {
        val match = matches.document(matchId)
        fetchFromServer(
            { match.get(Source.SERVER).await() },
            { match.collection(GAMES).get(Source.SERVER).await() },
            { gameId?.let { match.collection(GAMES).document(it).collection(NOTIFICATIONS).get(Source.SERVER).await() } },
        )
    }

    /** Executa les lectures en paral·lel; un error o la manca de connexió no són fatals (es queden les dades de la memòria cau). */
    private suspend fun fetchFromServer(vararg requests: suspend () -> Any?) {
        try {
            withTimeoutOrNull(REFRESH_TIMEOUT_MS) {
                coroutineScope { requests.map { request -> async { request() } }.awaitAll() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Error refrescant les dades", e)
        }
    }

    override fun match(matchId: String): Flow<Match?> =
        matches.document(matchId).observe()
            .map { FirestoreParsers.match(it) }
            .catch { error ->
                Log.w(TAG, "Error llegint el partit $matchId", error)
                emit(null)
            }
            .flowOn(Dispatchers.Default)

    override fun games(matchId: String): Flow<List<Game>> =
        matches.document(matchId).collection(GAMES).observe()
            .map { snapshot -> snapshot.documents.map { FirestoreParsers.game(it) }.sortedBy { it.number } }
            .catch { error ->
                Log.w(TAG, "Error llegint els jocs de $matchId", error)
                emit(emptyList())
            }
            .flowOn(Dispatchers.Default)

    /** Esdeveniments d'un joc, del més recent al més antic. */
    override fun events(matchId: String, gameId: String): Flow<List<GameEvent>> =
        matches.document(matchId).collection(GAMES).document(gameId).collection(NOTIFICATIONS).observe()
            .map { snapshot ->
                snapshot.documents
                    .map { FirestoreParsers.event(it) }
                    .filter { it.text.isNotBlank() }
                    .sortedByDescending { it.order }
            }
            .catch { error ->
                Log.w(TAG, "Error llegint els esdeveniments de $gameId", error)
                emit(emptyList())
            }
            .flowOn(Dispatchers.Default)

    private fun feed(query: Query, scope: CoroutineScope): StateFlow<Feed<List<Match>>> =
        restartFeeds.flatMapLatest {
            query.observe()
                .map { snapshot ->
                    Feed(snapshot.documents.mapNotNull { FirestoreParsers.match(it) }.sortedForState())
                }
                .retryWhen { cause, attempt ->
                    Log.w(TAG, "Error del listener de partits (intent $attempt)", cause)
                    emit(Feed(emptyList(), error = cause))
                    delay((1_000L shl attempt.coerceAtMost(5).toInt()))
                    true
                }
        }
            .flowOn(Dispatchers.Default)
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), Feed(emptyList(), loading = true))

    private fun List<Match>.sortedForState(): List<Match> {
        val state = firstOrNull()?.state ?: return this
        return if (state == MatchState.COMPLETED) sortedByDescending { it.startTime } else sortedBy { it.startTime }
    }

    private companion object {
        const val TAG = "MatchRepository"
        const val MATCHES = "Matches"
        const val GAMES = "Games"
        const val NOTIFICATIONS = "Notifications"
        const val STATE = "state"
        const val START_TIME = "startTime"
        const val REFRESH_TIMEOUT_MS = 10_000L
    }
}

internal fun Query.observe(): Flow<QuerySnapshot> = callbackFlow {
    val registration = addSnapshotListener { snapshot, error ->
        if (error != null) {
            close(error)
            return@addSnapshotListener
        }
        if (snapshot != null) trySend(snapshot)
    }
    awaitClose { registration.remove() }
}

internal fun DocumentReference.observe(): Flow<DocumentSnapshot> = callbackFlow {
    val registration = addSnapshotListener { snapshot, error ->
        if (error != null) {
            close(error)
            return@addSnapshotListener
        }
        if (snapshot != null) trySend(snapshot)
    }
    awaitClose { registration.remove() }
}
