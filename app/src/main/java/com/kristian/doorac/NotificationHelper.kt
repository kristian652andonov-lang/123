package com.kristian.doorac

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object NotificationHelper {
    const val CHANNEL_ID = "door_ac_status"
    const val NOTIFICATION_ID = 1001

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Статус на Врата и Климатик",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Постоянно показва дали вратата е заключена и климатикът изключен"
                setShowBadge(false)
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    fun buildNotification(context: Context, doorUnlocked: Boolean, acOn: Boolean): Notification {
        val doorText = if (doorUnlocked) "Врата: отключена" else "Врата: заключена"
        val acText = if (acOn) "Климатик: включен" else "Климатик: изключен"
        val allClear = !doorUnlocked && !acOn

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(if (allClear) R.drawable.ic_status_ok else R.drawable.ic_status_warning)
            .setContentTitle(if (allClear) "Всичко е готово" else "Провери преди да тръгнеш")
            .setContentText("$doorText   $acText")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$doorText\n$acText"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .build()
    }

    fun updateNotification(context: Context, doorUnlocked: Boolean, acOn: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context)
                .notify(NOTIFICATION_ID, buildNotification(context, doorUnlocked, acOn))
        }
    }
}
