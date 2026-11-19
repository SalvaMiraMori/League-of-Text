package com.leagueoftext.app.data

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Preferències de l'usuari, desades al dispositiu. */
class SettingsRepository(private val prefs: SharedPreferences) {

    private val _hideScores = MutableStateFlow(prefs.getBoolean(KEY_HIDE_SCORES, true))

    /** Com a Riot Mobile: els resultats s'amaguen fins que l'usuari els vol veure. */
    val hideScores: StateFlow<Boolean> = _hideScores.asStateFlow()

    private val _notificationsEnabled = MutableStateFlow(prefs.getBoolean(KEY_NOTIFICATIONS, true))
    val notificationsEnabled: StateFlow<Boolean> = _notificationsEnabled.asStateFlow()

    fun setHideScores(value: Boolean) {
        prefs.edit { putBoolean(KEY_HIDE_SCORES, value) }
        _hideScores.value = value
    }

    fun setNotificationsEnabled(value: Boolean) {
        prefs.edit { putBoolean(KEY_NOTIFICATIONS, value) }
        _notificationsEnabled.value = value
    }

    private companion object {
        const val KEY_HIDE_SCORES = "hide_scores"
        const val KEY_NOTIFICATIONS = "notifications_enabled"
    }
}
