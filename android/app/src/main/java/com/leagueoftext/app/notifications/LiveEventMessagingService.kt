package com.leagueoftext.app.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.leagueoftext.app.R
import com.leagueoftext.app.appContainer

/** Rep els missatges de dades que envia el backend per a cada esdeveniment d'un partit seguit. */
class LiveEventMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val container = applicationContext.appContainer
        if (!container.settings.notificationsEnabled.value) return

        val data = message.data
        val text = data["text"] ?: message.notification?.body ?: return
        // El backend antic no enviava matchId ni title (només gameAndTime): es dedueix del topic.
        val matchId = data["matchId"] ?: message.from?.removePrefix("/topics/") ?: return
        val title = data["title"] ?: data["gameAndTime"] ?: message.notification?.title ?: getString(R.string.app_name)

        LiveNotifications.show(applicationContext, matchId, title, text)
    }

    override fun onNewToken(token: String) {
        val container = applicationContext.appContainer
        if (container.firebaseConfigured) container.subscriptions.resubscribeAll()
    }
}
