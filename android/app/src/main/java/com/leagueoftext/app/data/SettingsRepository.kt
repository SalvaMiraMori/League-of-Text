package com.leagueoftext.app.data

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Preferències de l'usuari, desades al dispositiu.
 *
 * Fa servir *explicit backing fields* (estables des de Kotlin 2.4): fora de la classe la
 * propietat és un `StateFlow` de només lectura, i a dins es pot modificar directament,
 * sense el parell `_hideScores` / `asStateFlow()`.
 */
class SettingsRepository(private val prefs: SharedPreferences) {

    /** Com a Riot Mobile: els resultats s'amaguen fins que l'usuari els vol veure. */
    val hideScores: StateFlow<Boolean>
        field = MutableStateFlow(prefs.getBoolean(KEY_HIDE_SCORES, true))

    val notificationsEnabled: StateFlow<Boolean>
        field = MutableStateFlow(prefs.getBoolean(KEY_NOTIFICATIONS, true))

    fun setHideScores(value: Boolean) {
        prefs.edit { putBoolean(KEY_HIDE_SCORES, value) }
        hideScores.value = value
    }

    fun setNotificationsEnabled(value: Boolean) {
        prefs.edit { putBoolean(KEY_NOTIFICATIONS, value) }
        notificationsEnabled.value = value
    }

    private companion object {
        const val KEY_HIDE_SCORES = "hide_scores"
        const val KEY_NOTIFICATIONS = "notifications_enabled"
    }
}
