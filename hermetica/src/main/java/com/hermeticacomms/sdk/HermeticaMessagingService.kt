package com.hermeticacomms.sdk

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Register this in your manifest (or extend it and call super) to send FCM tokens to Hermetica and to show Hermetica pushes
 * that arrive while the app is in the foreground (Android shows them itself when the app is in the background).
 * Needs `com.google.firebase:firebase-messaging` in your app.
 */
open class HermeticaMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Hermetica.setPushToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        if (message.data[Push.MESSAGE_ID] == null) return // not ours
        val n = message.notification
        ForegroundNotification.show(this, n?.title ?: message.data["title"], n?.body ?: message.data["body"], message.data)
    }
}

internal object FcmToken {
    /** Firebase may not be configured in this process; the token then arrives through onNewToken later. */
    fun fetch(onToken: (String) -> Unit) {
        try { FirebaseMessaging.getInstance().token.addOnSuccessListener { onToken(it) } } catch (_: IllegalStateException) { }
    }
}

internal object ForegroundNotification {
    private const val CHANNEL = "hermetica"

    @SuppressLint("MissingPermission", "NotificationPermission") // checked below: nothing is shown without permission
    fun show(context: Context, title: String?, body: String?, data: Map<String, String>) {
        if (title == null && body == null) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 24 && !nm.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Notifications", NotificationManager.IMPORTANCE_DEFAULT))
        // Tapping opens the launcher activity with the push data as extras, like a background FCM notification does.
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        for ((k, v) in data) launch.putExtra(k, v)
        val id = (data[Push.MESSAGE_ID] ?: "").hashCode()
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val icon = context.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(context)
        nm.notify(id, builder.setSmallIcon(icon).setContentTitle(title).setContentText(body).setAutoCancel(true).setContentIntent(PendingIntent.getActivity(context, id, launch, flags)).build())
    }
}
