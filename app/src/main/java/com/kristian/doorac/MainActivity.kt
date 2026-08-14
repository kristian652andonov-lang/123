package com.kristian.doorac

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var doorButton: Button
    private lateinit var acButton: Button
    private lateinit var doorRing: NeonRingView
    private lateinit var acRing: NeonRingView

    private var doorUnlocked = false
    private var acOn = false

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            NotificationHelper.updateNotification(this, doorUnlocked, acOn)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        doorUnlocked = prefs.getBoolean(KEY_DOOR_UNLOCKED, false)
        acOn = prefs.getBoolean(KEY_AC_ON, false)

        doorButton = findViewById(R.id.doorButton)
        acButton = findViewById(R.id.acButton)
        doorRing = findViewById(R.id.doorRing)
        acRing = findViewById(R.id.acRing)

        NotificationHelper.createChannel(this)
        requestNotificationPermissionIfNeeded()

        updateDoorUI()
        updateAcUI()
        startStatusService()

        doorButton.setOnClickListener {
            doorUnlocked = !doorUnlocked
            prefs.edit().putBoolean(KEY_DOOR_UNLOCKED, doorUnlocked).apply()
            updateDoorUI()
            NotificationHelper.updateNotification(this, doorUnlocked, acOn)
        }

        acButton.setOnClickListener {
            acOn = !acOn
            prefs.edit().putBoolean(KEY_AC_ON, acOn).apply()
            updateAcUI()
            NotificationHelper.updateNotification(this, doorUnlocked, acOn)
        }
    }

    private fun startStatusService() {
        val intent = Intent(this, StatusService::class.java)
        ContextCompat.startForegroundService(this, intent)
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
        if (doorUnlocked) {
            doorButton.text = "${getString(R.string.door_label)}\n${getString(R.string.door_unlocked)}"
            doorRing.ringColor = ResourcesCompat.getColor(resources, R.color.neon_green, theme)
        } else {
            doorButton.text = "${getString(R.string.door_label)}\n${getString(R.string.door_locked)}"
            doorRing.ringColor = ResourcesCompat.getColor(resources, R.color.neon_red, theme)
        }
    }

    private fun updateAcUI() {
        if (acOn) {
            acButton.text = "${getString(R.string.ac_label)}\n${getString(R.string.ac_on)}"
            acRing.ringColor = ResourcesCompat.getColor(resources, R.color.neon_green, theme)
        } else {
            acButton.text = "${getString(R.string.ac_label)}\n${getString(R.string.ac_off)}"
            acRing.ringColor = ResourcesCompat.getColor(resources, R.color.neon_red, theme)
        }
    }

    companion object {
        const val PREFS_NAME = "status"
        const val KEY_DOOR_UNLOCKED = "door_unlocked"
        const val KEY_AC_ON = "ac_on"
    }
}
