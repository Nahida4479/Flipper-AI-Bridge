package com.flipperai.bridge

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

object Notifier {
    private const val CHANNEL = "updates"
    private const val CHANNEL_HIGH = "updated"
    private const val ID_UPDATE = 21
    private const val ID_UPDATED = 22

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_DEFAULT)
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_HIGH, "Update finished", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun show(context: Context, id: Int, title: String, text: String, channel: String = CHANNEL) {
        if (!canNotify(context)) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(id, notification)
    }

    fun updateAvailable(context: Context, text: String) = show(context, ID_UPDATE, "Update available", text)

    fun updated(context: Context, version: String) =
        show(context, ID_UPDATED, "Flipper AI Bridge updated", "Now running $version. Tap to open.", CHANNEL_HIGH)
}
