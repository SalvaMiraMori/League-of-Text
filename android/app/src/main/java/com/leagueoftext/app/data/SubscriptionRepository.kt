package com.leagueoftext.app.data

import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Partits que segueix l'usuari.
 *
 * * Es desen al dispositiu, de manera que es poden seguir partits sense iniciar sessió.
 * * Cada partit és un *topic* de FCM: seguir-lo vol dir subscriure-s'hi.
 * * Amb la sessió iniciada, la llista es sincronitza a `Users/{uid}` amb `arrayUnion` /
 *   `arrayRemove` (abans es llegia, es modificava i s'escrivia el document sencer, i dos
 *   canvis seguits es podien trepitjar).
 */
class SubscriptionRepository(
    private val prefs: SharedPreferences,
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth,
    private val scope: CoroutineScope,
) {
    private val messaging: FirebaseMessaging get() = FirebaseMessaging.getInstance()

    private val _subscriptions = MutableStateFlow(prefs.getStringSet(KEY, null)?.toSet() ?: emptySet())
    val subscriptions: StateFlow<Set<String>> = _subscriptions.asStateFlow()

    fun isSubscribed(matchId: String): Boolean = matchId in _subscriptions.value

    fun toggle(matchId: String) {
        if (isSubscribed(matchId)) unsubscribe(matchId) else subscribe(matchId)
    }

    fun subscribe(matchId: String) {
        store { it + matchId }
        scope.launch {
            runCatching { messaging.subscribeToTopic(matchId).await() }
                .onFailure { Log.w(TAG, "No s'ha pogut subscriure a $matchId", it) }
            syncRemote(matchId, add = true)
        }
    }

    fun unsubscribe(matchId: String) {
        store { it - matchId }
        scope.launch {
            runCatching { messaging.unsubscribeFromTopic(matchId).await() }
                .onFailure { Log.w(TAG, "No s'ha pogut donar de baixa de $matchId", it) }
            syncRemote(matchId, add = false)
        }
    }

    /** En iniciar sessió: uneix la llista local amb la del compte i les deixa iguals. */
    fun onSignedIn() {
        val uid = auth.currentUser?.uid ?: return
        scope.launch {
            runCatching {
                val doc = db.collection(USERS).document(uid).get().await()
                val remote = (doc.get(FIELD) as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()
                val merged = remote + _subscriptions.value
                store { merged }
                (merged - remote).takeIf { it.isNotEmpty() }?.let { missing ->
                    db.collection(USERS).document(uid)
                        .set(
                            mapOf(FIELD to FieldValue.arrayUnion(*missing.toTypedArray()), UPDATED to FieldValue.serverTimestamp()),
                            SetOptions.merge(),
                        ).await()
                }
                resubscribeAll()
            }.onFailure { Log.w(TAG, "No s'han pogut sincronitzar les subscripcions", it) }
        }
    }

    /** Torna a subscriure tots els topics (p. ex. quan FCM genera un token nou). */
    fun resubscribeAll() {
        val ids = _subscriptions.value
        scope.launch {
            ids.forEach { id ->
                runCatching { messaging.subscribeToTopic(id).await() }
                    .onFailure { Log.w(TAG, "No s'ha pogut subscriure a $id", it) }
            }
        }
    }

    private suspend fun syncRemote(matchId: String, add: Boolean) {
        val uid = auth.currentUser?.uid ?: return
        val change = if (add) FieldValue.arrayUnion(matchId) else FieldValue.arrayRemove(matchId)
        runCatching {
            db.collection(USERS).document(uid)
                .set(mapOf(FIELD to change, UPDATED to FieldValue.serverTimestamp()), SetOptions.merge())
                .await()
        }.onFailure { Log.w(TAG, "No s'ha pogut desar la subscripció al compte", it) }
    }

    private fun store(transform: (Set<String>) -> Set<String>) {
        _subscriptions.update(transform)
        prefs.edit { putStringSet(KEY, HashSet(_subscriptions.value)) }
    }

    private companion object {
        const val TAG = "Subscriptions"
        const val KEY = "subscribed_matches"
        const val USERS = "Users"
        const val FIELD = "subscribedMatches"
        const val UPDATED = "updatedAt"
    }
}
