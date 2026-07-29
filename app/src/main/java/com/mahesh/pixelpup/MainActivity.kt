package com.mahesh.pixelpup

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var overlayButton: Button
    private lateinit var statusText: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val statusRunnable = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("pixelpup_prefs", MODE_PRIVATE)

        overlayButton = findViewById(R.id.buttonGrantOverlay)
        statusText = findViewById(R.id.textStatus)
        val startButton = findViewById<Button>(R.id.buttonStart)
        val stopButton = findViewById<Button>(R.id.buttonStop)
        val sizeSeekBar = findViewById<SeekBar>(R.id.seekSize)
        val speedSeekBar = findViewById<SeekBar>(R.id.seekSpeed)
        val volumeSeekBar = findViewById<SeekBar>(R.id.seekVolume)
        val quietSwitch = findViewById<Switch>(R.id.switchQuiet)

        sizeSeekBar.max = 72
        sizeSeekBar.progress = (prefs.getInt("size_dp", 84) - 48).coerceIn(0, 72)
        speedSeekBar.max = 100
        speedSeekBar.progress = prefs.getInt("speed_pct", 50)
        volumeSeekBar.max = 100
        volumeSeekBar.progress = prefs.getInt("volume_pct", 80)
        quietSwitch.isChecked = prefs.getBoolean("quiet_mode", false)

        sizeSeekBar.setOnSeekBarChangeListener(simpleSeekListener { progress ->
            prefs.edit().putInt("size_dp", 48 + progress).apply()
        })
        speedSeekBar.setOnSeekBarChangeListener(simpleSeekListener { progress ->
            prefs.edit().putInt("speed_pct", progress).apply()
        })
        volumeSeekBar.setOnSeekBarChangeListener(simpleSeekListener { progress ->
            prefs.edit().putInt("volume_pct", progress).apply()
        })
        quietSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("quiet_mode", isChecked).apply()
        }

        overlayButton.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        startButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(
                        this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001
                    )
                }
            }
            val serviceIntent = Intent(this, PetOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }

        stopButton.setOnClickListener {
            stopService(Intent(this, PetOverlayService::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshOverlayButtonVisibility()
        handler.post(statusRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(statusRunnable)
    }

    private fun refreshOverlayButtonVisibility() {
        val canDraw = Settings.canDrawOverlays(this)
        overlayButton.visibility = if (canDraw) View.GONE else View.VISIBLE
    }

    private fun refreshStatus() {
        val mood = prefs.getString("mood", "CONTENT")
        val state = prefs.getString("state", "IDLE")
        val hunger = prefs.getFloat("hunger", 0f).toInt()
        val energy = prefs.getFloat("energy", 0f).toInt()
        val bladder = prefs.getFloat("bladder", 0f).toInt()
        val affection = prefs.getFloat("affection", 0f).toInt()
        statusText.text = "Mood: $mood   State: $state\n" +
            "Hunger: $hunger   Energy: $energy\n" +
            "Bladder: $bladder   Affection: $affection"
    }

    private fun simpleSeekListener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress)
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }
}
