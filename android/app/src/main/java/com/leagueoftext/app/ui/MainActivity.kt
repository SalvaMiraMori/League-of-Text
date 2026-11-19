package com.leagueoftext.app.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leagueoftext.app.R
import com.leagueoftext.app.appContainer
import com.leagueoftext.app.notifications.LiveNotifications
import com.leagueoftext.app.ui.components.EmptyState
import com.leagueoftext.app.ui.theme.LeagueOfTextTheme
import com.leagueoftext.app.ui.theme.RiotColors
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /** Partit que s'ha d'obrir en tocar una notificació. */
    private val pendingMatchId = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)

        val firebaseConfigured = appContainer.firebaseConfigured
        setContent {
            LeagueOfTextTheme {
                if (firebaseConfigured) {
                    val matchId by pendingMatchId.collectAsStateWithLifecycle()
                    LeagueOfTextNavHost(pendingMatchId = matchId, onPendingMatchHandled = { pendingMatchId.value = null })
                } else {
                    Box(
                        Modifier.fillMaxSize().background(RiotColors.Background).safeDrawingPadding(),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyState(
                            Icons.Filled.Settings,
                            stringResource(R.string.firebase_missing_title),
                            stringResource(R.string.firebase_missing_message),
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra(LiveNotifications.EXTRA_MATCH_ID)?.let { pendingMatchId.value = it }
    }
}
