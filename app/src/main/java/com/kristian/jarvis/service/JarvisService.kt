package com.kristian.jarvis.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.kristian.jarvis.MainActivity
import com.kristian.jarvis.R
import com.kristian.jarvis.core.JarvisEngine
import com.kristian.jarvis.tools.JarvisNotifications
import com.kristian.jarvis.ui.AssistantState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps JARVIS awake.
 *
 * The engine lives here rather than in the Activity so listening, thinking and
 * speaking all carry on with the app backgrounded or the screen off. The UI
 * binds to this and observes the same engine, so there is only ever one
 * microphone owner and one conversation.
 *
 * Started only once RECORD_AUDIO is granted: a microphone-type foreground
 * service without the permission is rejected on Android 14+, and an
 * always-listening service that cannot listen is pointless anyway.
 */
class JarvisService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var engine: JarvisEngine
        private set

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        val service: JarvisService get() = this@JarvisService
    }

    override fun onCreate() {
        super.onCreate()
        JarvisNotifications.ensureChannels(this)
        engine = JarvisEngine(applicationContext, scope)
        startInForeground(AssistantState.IDLE)
        engine.start(micGranted = true)
        observeState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Worth restarting if Android kills us: the whole point is being there.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun observeState() {
        scope.launch {
            engine.uiState
                .map { it.state }
                .distinctUntilChanged()
                .collect { state -> updateNotification(state) }
        }
    }

    private fun startInForeground(state: AssistantState) {
        val notification = buildNotification(state)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            // Almost always a revoked microphone permission. Nothing to do but
            // stand down cleanly rather than crash on the user's home screen.
            Log.w(TAG, "Could not enter the foreground", t)
            stopSelf()
        }
    }

    private fun updateNotification(state: AssistantState) {
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(NOTIFICATION_ID, buildNotification(state))
        }
    }

    private fun buildNotification(state: AssistantState): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, JarvisService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val summary = when (state) {
            AssistantState.IDLE -> "Standing by - say \"Jarvis\""
            AssistantState.LISTENING -> "Listening"
            AssistantState.THINKING -> "Thinking"
            AssistantState.SPEAKING -> "Speaking"
            AssistantState.ERROR -> "Something went wrong - tap to see"
        }

        return NotificationCompat.Builder(this, JarvisNotifications.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("JARVIS")
            .setContentText(summary)
            .setContentIntent(open)
            .addAction(0, "Stand down", stop)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        engine.release()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "JarvisService"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.kristian.jarvis.STOP"

        fun start(context: Context) {
            val intent = Intent(context, JarvisService::class.java)
            runCatching { context.startForegroundService(intent) }
                .onFailure { Log.w(TAG, "Could not start the service", it) }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, JarvisService::class.java).setAction(ACTION_STOP)
                )
            }
        }
    }
}
