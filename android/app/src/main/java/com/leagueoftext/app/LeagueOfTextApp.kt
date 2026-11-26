package com.leagueoftext.app

import android.app.Application
import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.leagueoftext.app.data.AuthRepository
import com.leagueoftext.app.data.MatchRepository
import com.leagueoftext.app.data.MatchSource
import com.leagueoftext.app.data.MockMatchRepository
import com.leagueoftext.app.data.SettingsRepository
import com.leagueoftext.app.data.SubscriptionRepository
import com.leagueoftext.app.notifications.LiveNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class LeagueOfTextApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        LiveNotifications.createChannel(this)
    }
}

/**
 * Dependències de l'app (injecció manual). Abans cada ViewModel era un `object` global que
 * creava el seu propi `DatabaseAdapter`, i aquest heretava d'`AppCompatActivity`.
 */
class AppContainer(context: Context) {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** `false` si falta `google-services.json`: l'app mostra com configurar-la en lloc de petar. */
    val firebaseConfigured: Boolean = FirebaseApp.getApps(context).isNotEmpty()

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val settings = SettingsRepository(prefs)

    private val firestore: FirebaseFirestore by lazy {
        FirebaseFirestore.getInstance().apply {
            firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(PersistentCacheSettings.newBuilder().setSizeBytes(CACHE_BYTES).build())
                .build()
        }
    }

    private val firebaseAuth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    /**
     * Partits de Firestore o, si s'ha compilat amb `leagueoftext.mockData=true` (només debug),
     * partits inventats a Pròxims i En directe; Resultats continua llegint Firestore.
     */
    val matches: MatchSource by lazy {
        val firestoreMatches = if (firebaseConfigured) MatchRepository(firestore, appScope) else null
        if (BuildConfig.MOCK_DATA) MockMatchRepository(firestoreMatches, appScope)
        else checkNotNull(firestoreMatches) { "Firebase no està configurat" }
    }
    val auth: AuthRepository by lazy { AuthRepository(firebaseAuth) }
    val subscriptions: SubscriptionRepository by lazy {
        SubscriptionRepository(prefs, firestore, firebaseAuth, appScope)
    }

    private companion object {
        const val PREFS = "league_of_text"
        const val CACHE_BYTES = 50L * 1024 * 1024
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as LeagueOfTextApp).container
