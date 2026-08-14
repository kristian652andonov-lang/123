package com.kristian.doorac

import android.app.Service
import android.content.Intent
import android.os.IBinder

class StatusService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationHelper.createChannel(this)

        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
        val doorUnlocked = prefs.getBoolean(MainActivity.KEY_DOOR_UNLOCKED, false)
        val acOn = prefs.getBoolean(MainActivity.KEY_AC_ON, false)

        val notification = NotificationHelper.buildNotification(this, doorUnlocked, acOn)
        startForeground(NotificationHelper.NOTIFICATION_ID, notification)

        return START_STICKY
    }
}
