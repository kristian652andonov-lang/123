package com.kristian.jarvis.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.kristian.jarvis.MainActivity
import com.kristian.jarvis.R
import com.kristian.jarvis.core.JarvisEngine
import com.kristian.jarvis.core.Phase
import com.kristian.jarvis.voice.Listener
import java.util.Locale
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Always-on wake word. Runs the recogniser in a loop and only wakes Jarvis proper when it
 * hears his name.
 *
 * It listens exclusively while the engine is idle: the moment a real conversation starts,
 * the loop stands down so the two recognisers never fight over the microphone.
 */
class WakeWordService : LifecycleService() {

    private lateinit var engine: JarvisEngine
    private lateinit var listener: Listener
    private val handler = Handler(Looper.getMainLooper())

    private var loopWanted = false
    private var lastTrigger = 0L

    override fun onCreate() {
        super.onCreate()
        engine = JarvisEngine.get(this)
        listener = Listener(this)

        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )

        if (!hasMicrophone()) {
            stopSelf()
            return
        }

        lifecycleScope.launch {
            engine.phase.collectLatest { phase ->
                if (phase == Phase.IDLE) resumeLoop() else pauseLoop()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            engine.prefs.update { it.copy(wakeWordEnabled = false) }
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        loopWanted = false
        handler.removeCallbacksAndMessages(null)
        listener.destroy()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- the loop

    private fun resumeLoop() {
        if (loopWanted) return
        loopWanted = true
        scheduleListen(400L)
    }

    private fun pauseLoop() {
        loopWanted = false
        handler.removeCallbacksAndMessages(null)
        listener.abort()
    }

    private fun scheduleListen(delay: Long) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (loopWanted && hasMicrophone()) {
                listener.start(wakeCallbacks, preferOffline = true)
            }
        }, delay)
    }

    private val wakeCallbacks = object : Listener.Callbacks {
        override fun onFinal(text: String) {
            if (!loopWanted) return
            val command = commandAfterWakeWord(text)
            if (command == null) {
                scheduleListen(250L)
                return
            }
            val now = System.currentTimeMillis()
            if (now - lastTrigger < 1_500L) {
                scheduleListen(500L)
                return
            }
            lastTrigger = now
            trigger(command)
        }

        override fun onFailure(code: Int, reason: String) {
            if (!loopWanted) return
            // No match and timeouts are the normal case while nobody is talking; a busy or
            // dead recogniser needs a longer breather before we try again.
            val stumbled = code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                code == SpeechRecognizer.ERROR_CLIENT
            scheduleListen(if (stumbled) 2_000L else 350L)
        }
    }

    private fun trigger(command: String) {
        pauseLoop()
        openApp()
        if (command.isBlank()) {
            engine.say("Yes?")
            handler.postDelayed({ engine.listen() }, 900L)
        } else {
            engine.submit(command)
        }
    }

    /**
     * Returns the request that followed the wake word, an empty string when the wake word
     * was heard on its own, or null when Jarvis was not addressed at all.
     */
    private fun commandAfterWakeWord(heard: String): String? {
        val text = heard.lowercase(Locale.UK)
        val hit = WAKE_WORDS
            .mapNotNull { word -> text.indexOf(word).takeIf { it >= 0 }?.let { it to word } }
            .minByOrNull { it.first }
            ?: return null
        return heard.substring((hit.first + hit.second.length).coerceAtMost(heard.length))
            .trimStart(' ', ',', '.', '!', '?', ':', ';')
            .trim()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { startActivity(intent) }
    }

    private fun hasMicrophone(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- notification

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.wake_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.wake_notice_title))
            .setContentText(getString(R.string.wake_notice_text))
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "jarvis_wake"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "com.kristian.jarvis.STOP_WAKE"

        private val WAKE_WORDS = listOf("jarvis", "jervis", "javis", "jarvi", "charvis")

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, WakeWordService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }
    }
}
