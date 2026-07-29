package com.mahesh.pixelpup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Choreographer
import android.view.Gravity
import android.view.WindowManager
import java.util.Calendar

class PetOverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.mahesh.pixelpup.action.STOP"
        const val CHANNEL_ID = "pixel_pup_channel"
        const val NOTIFICATION_ID = 1001
    }

    private lateinit var windowManager: WindowManager
    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var dogView: DogView
    private lateinit var brain: PetBrain
    private lateinit var soundEngine: SoundEngine
    private lateinit var touchController: TouchController
    private lateinit var prefs: SharedPreferences

    private val handler = Handler(Looper.getMainLooper())
    private var lastFrameTimeNanos: Long = 0L
    private var viewAdded = false
    private var lastAppliedSizeDp = -1

    private val choreographer by lazy { Choreographer.getInstance() }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val dtSeconds = if (lastFrameTimeNanos == 0L) {
                0.016f
            } else {
                ((frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000f).coerceIn(0f, 0.1f)
            }
            lastFrameTimeNanos = frameTimeNanos

            applySettingsFromPrefs()
            brain.tick(dtSeconds, currentHourOfDay())

            for (event in brain.events) {
                when (event) {
                    is PetEvent.Sound -> soundEngine.play(event.type)
                    is PetEvent.Particles -> dogView.spawnParticles(event.type)
                    is PetEvent.Bubble -> { /* already reflected in brain.thoughtBubbleText */ }
                }
            }

            updateWindowPosition()
            dogView.invalidate()
            writeLiveStatus()

            choreographer.postFrameCallback(this)
        }
    }

    private val autosaveRunnable = object : Runnable {
        override fun run() {
            saveState()
            handler.postDelayed(this, 30_000L)
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (level >= 0 && scale > 0) {
                        brain.onBatteryChanged((level * 100) / scale)
                    }
                }
                Intent.ACTION_POWER_CONNECTED -> brain.onPowerConnected()
                Intent.ACTION_POWER_DISCONNECTED -> brain.onPowerDisconnected()
                Intent.ACTION_SCREEN_ON -> brain.onScreenOn()
                Intent.ACTION_USER_PRESENT -> brain.onUserPresent()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("pixelpup_prefs", MODE_PRIVATE)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        brain = PetBrain()

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        brain.setScreenSize(metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())

        val density = metrics.density
        val sizeDp = prefs.getInt("size_dp", 84)
        lastAppliedSizeDp = sizeDp
        brain.setPetHeightPx(sizeDp * density)

        restoreState()
        val lastSeen = prefs.getLong("last_seen_millis", System.currentTimeMillis())
        val elapsedSeconds = ((System.currentTimeMillis() - lastSeen) / 1000L).toFloat().coerceAtLeast(0f)
        brain.applyOfflineDecay(elapsedSeconds)

        soundEngine = SoundEngine(this)

        dogView = DogView(this)
        dogView.brain = brain
        dogView.sizeDp = sizeDp.toFloat()

        touchController = TouchController(this, brain, windowManager)
        touchController.attach(dogView)
        touchController.onSendHome = { stopSelf() }
        touchController.layoutParamsProvider = { layoutParams }
        touchController.onWindowMoved = { lx, ly -> updateWindowFromTouch(lx, ly) }
        dogView.setOnTouchListener(touchController)

        addOverlayView()
        registerReceivers()
        startPetForeground()

        choreographer.postFrameCallback(frameCallback)
        handler.postDelayed(autosaveRunnable, 30_000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        choreographer.removeFrameCallback(frameCallback)
        handler.removeCallbacks(autosaveRunnable)
        saveState()
        try {
            unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            // Not registered, or already torn down.
        }
        if (viewAdded) {
            try {
                windowManager.removeView(dogView)
            } catch (e: Exception) {
                // View already detached.
            }
            viewAdded = false
        }
        soundEngine.release()
    }

    private fun addOverlayView() {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = brain.x.toInt()
            y = brain.y.toInt()
        }
        windowManager.addView(dogView, layoutParams)
        viewAdded = true
    }

    private fun updateWindowPosition() {
        if (!viewAdded || brain.state == PetState.DRAGGED) return
        layoutParams.x = brain.x.toInt()
        layoutParams.y = brain.y.toInt()
        try {
            windowManager.updateViewLayout(dogView, layoutParams)
        } catch (e: Exception) {
            // View may be mid-teardown; safe to ignore.
        }
    }

    private fun updateWindowFromTouch(x: Int, y: Int) {
        if (!viewAdded) return
        layoutParams.x = x
        layoutParams.y = y
        try {
            windowManager.updateViewLayout(dogView, layoutParams)
        } catch (e: Exception) {
            // View may be mid-teardown; safe to ignore.
        }
    }

    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(batteryReceiver, filter)
        }
    }

    private fun startPetForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, "Pixel Pup", NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)

        val stopIntent = Intent(this, PetOverlayService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .addAction(0, getString(R.string.call_pup_back), stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun applySettingsFromPrefs() {
        val speedPct = prefs.getInt("speed_pct", 50)
        brain.setSpeedMultiplier(0.5f + (speedPct / 100f) * 1.5f)
        soundEngine.setQuietMode(prefs.getBoolean("quiet_mode", false))

        val sizeDp = prefs.getInt("size_dp", 84)
        if (sizeDp != lastAppliedSizeDp) {
            lastAppliedSizeDp = sizeDp
            dogView.sizeDp = sizeDp.toFloat()
            brain.setPetHeightPx(sizeDp * resources.displayMetrics.density)
            dogView.requestLayout()
        }
    }

    private fun currentHourOfDay(): Int {
        return Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    }

    private fun writeLiveStatus() {
        prefs.edit()
            .putString("mood", brain.mood.name)
            .putString("state", brain.state.name)
            .putFloat("hunger", brain.needs.hunger)
            .putFloat("energy", brain.needs.energy)
            .putFloat("bladder", brain.needs.bladder)
            .putFloat("affection", brain.needs.affection)
            .apply()
    }

    private fun saveState() {
        val snapshot = brain.snapshotForSave(System.currentTimeMillis())
        prefs.edit()
            .putFloat("pos_x", snapshot.x)
            .putFloat("pos_y", snapshot.y)
            .putFloat("hunger", snapshot.hunger)
            .putFloat("energy", snapshot.energy)
            .putFloat("bladder", snapshot.bladder)
            .putFloat("affection", snapshot.affection)
            .putInt("battery_percent", snapshot.batteryPercent)
            .putBoolean("is_charging", snapshot.isCharging)
            .putLong("last_seen_millis", snapshot.lastSeenTimestampMillis)
            .apply()
    }

    private fun restoreState() {
        val save = PetSaveState(
            x = prefs.getFloat("pos_x", 100f),
            y = prefs.getFloat("pos_y", 800f),
            hunger = prefs.getFloat("hunger", 20f),
            energy = prefs.getFloat("energy", 80f),
            bladder = prefs.getFloat("bladder", 10f),
            affection = prefs.getFloat("affection", 70f),
            batteryPercent = prefs.getInt("battery_percent", 100),
            isCharging = prefs.getBoolean("is_charging", false),
            lastSeenTimestampMillis = prefs.getLong("last_seen_millis", System.currentTimeMillis())
        )
        brain.restore(save)
    }
}
