package com.kristian.doorac

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var doorButton: Button
    private lateinit var acButton: Button

    private var doorLocked = true
    private var acOff = true

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            NotificationHelper.updateNotification(this, doorLocked, acOff)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        doorLocked = prefs.getBoolean(KEY_DOOR_LOCKED, true)
        acOff = prefs.getBoolean(KEY_AC_OFF, true)

        doorButton = findViewById(R.id.doorButton)
        acButton = findViewById(R.id.acButton)

        NotificationHelper.createChannel(this)
        requestNotificationPermissionIfNeeded()

        updateDoorUI()
        updateAcUI()
        NotificationHelper.updateNotification(this, doorLocked, acOff)

        doorButton.setOnClickListener {
            doorLocked = !doorLocked
            prefs.edit().putBoolean(KEY_DOOR_LOCKED, doorLocked).apply()
            updateDoorUI()
            NotificationHelper.updateNotification(this, doorLocked, acOff)
        }

        acButton.setOnClickListener {
            acOff = !acOff
            prefs.edit().putBoolean(KEY_AC_OFF, acOff).apply()
            updateAcUI()
            NotificationHelper.updateNotification(this, doorLocked, acOff)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun updateDoorUI() {
        if (doorLocked) {
            doorButton.text = "Врата\nЗаключена 🔒"
            doorButton.setBackgroundResource(R.drawable.bg_button_safe)
        } else {
            doorButton.text = "Врата\nОтключена 🔓"
            doorButton.setBackgroundResource(R.drawable.bg_button_warning)
        }
    }

    private fun updateAcUI() {
        if (acOff) {
            acButton.text = "Климатик\nИзключен ❄️"
            acButton.setBackgroundResource(R.drawable.bg_button_safe)
        } else {
            acButton.text = "Климатик\nВключен 🔥"
            acButton.setBackgroundResource(R.drawable.bg_button_warning)
        }
    }

    companion object {
        private const val PREFS_NAME = "status"
        private const val KEY_DOOR_LOCKED = "door_locked"
        private const val KEY_AC_OFF = "ac_off"
    }
}
