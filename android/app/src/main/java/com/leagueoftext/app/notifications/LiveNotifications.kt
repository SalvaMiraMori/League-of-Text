package com.leagueoftext.app.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.leagueoftext.app.R
import com.leagueoftext.app.ui.MainActivity
import java.util.concurrent.atomic.AtomicInteger

object LiveNotifications {

    const val CHANNEL_ID = "live_events"
    const val EXTRA_MATCH_ID = "matchId"

    private val nextId = AtomicInteger((System.currentTimeMillis() % 100_000).toInt())

    fun createChannel(context: Context) {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
            .setName(context.getString(R.string.channel_live_name))
            .setDescription(context.getString(R.string.channel_live_description))
            .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Mostra un esdeveniment. Les notificacions d'un mateix partit s'agrupen, i en tocar-les
     * s'obre directament el partit.
     */
    @SuppressLint("MissingPermission") // es comprova a canNotify()
    fun show(context: Context, matchId: String, title: String, text: String) {
        if (!canNotify(context)) return
        val manager = NotificationManagerCompat.from(context)
        val group = "match_$matchId"
        val contentIntent = PendingIntent.getActivity(
            context,
            matchId.hashCode(),
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_MATCH_ID, matchId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val color = ContextCompat.getColor(context, R.color.gold)

        val event = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(color)
            .setContentTitle(title)
            .setContentText(text.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setGroup(group)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        val summary = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(color)
            .setContentTitle(title.substringBefore(" · "))
            .setGroup(group)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        manager.notify(nextId.incrementAndGet(), event)
        manager.notify(group.hashCode(), summary)
    }
}
