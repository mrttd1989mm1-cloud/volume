package com.example.volumewake

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.VolumeProviderCompat
import kotlin.math.sqrt

/**
 * Service này có 2 cơ chế đánh thức màn hình khi đang tắt:
 *
 * 1) Phím Volume: dùng MediaSession + VolumeProvider để "cướp" phím Volume,
 *    NHƯNG chỉ active khi màn hình đang tắt (tự tắt khi màn sáng để volume
 *    nhạc/chuông hoạt động bình thường).
 *
 * 2) Gõ đúp vào thân máy (tuỳ chọn, tốn pin hơn): dùng cảm biến gia tốc để
 *    phát hiện 2 cú gõ liên tiếp lên vỏ máy -> đánh thức màn hình. Cách này
 *    KHÔNG phải double-tap lên mặt kính cảm ứng (điều đó cần firmware chip
 *    cảm ứng hỗ trợ, app không can thiệp được), mà là phát hiện rung động
 *    vật lý qua accelerometer.
 */
class VolumeWakeService : Service(), SensorEventListener {

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var powerManager: PowerManager
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var partialWakeLock: PowerManager.WakeLock? = null

    private var currentVolume = 5
    private val maxVolume = 10

    private var doubleTapEnabled = false
    private var sensorListenerActive = false

    // --- Tham số nhận diện "gõ đúp" ---
    private val tapThreshold = 18f          // độ nhạy: càng thấp càng dễ trigger (dễ nhầm)
    private val minGapBetweenPeaksMs = 40L  // khoảng cách tối thiểu giữa 2 đỉnh để không đếm trùng 1 cú gõ
    private val maxGapForDoubleTapMs = 600L // 2 cú gõ phải cách nhau tối đa chừng này để tính là "gõ đúp"
    private val cooldownAfterTriggerMs = 1200L

    private var lastPeakTime = 0L
    private var firstTapTime = 0L
    private var lastTriggerTime = 0L

    companion object {
        private const val CHANNEL_ID = "volume_wake_channel"
        private const val NOTIFICATION_ID = 1
        const val PREFS_NAME = "volume_wake_prefs"
        const val KEY_DOUBLE_TAP = "double_tap_enabled"
        const val EXTRA_DOUBLE_TAP_ENABLED = "extra_double_tap_enabled"
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    if (::mediaSession.isInitialized) mediaSession.isActive = true
                    if (doubleTapEnabled) startSensorListening()
                }
                Intent.ACTION_SCREEN_ON -> {
                    if (::mediaSession.isInitialized) mediaSession.isActive = false
                    stopSensorListening()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        val prefs: SharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        doubleTapEnabled = prefs.getBoolean(KEY_DOUBLE_TAP, false)

        startForegroundServiceWithNotification()
        setupMediaSession()
        registerScreenStateReceiver()

        // Nếu service khởi động ngay lúc màn hình đang tắt sẵn
        if (!powerManager.isInteractive && doubleTapEnabled) {
            startSensorListening()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && intent.hasExtra(EXTRA_DOUBLE_TAP_ENABLED)) {
            doubleTapEnabled = intent.getBooleanExtra(EXTRA_DOUBLE_TAP_ENABLED, false)
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_DOUBLE_TAP, doubleTapEnabled).apply()

            if (!powerManager.isInteractive) {
                if (doubleTapEnabled) startSensorListening() else stopSensorListening()
            }
        }
        return START_STICKY
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "VolumeWakeSession")

        val volumeProvider = object : VolumeProviderCompat(
            VOLUME_CONTROL_ABSOLUTE,
            maxVolume,
            currentVolume
        ) {
            override fun onAdjustVolume(direction: Int) {
                if (direction != 0) wakeUpScreen()
            }

            override fun onSetVolumeTo(volume: Int) {
                currentVolume = volume
                this.currentVolume = volume
            }
        }

        mediaSession.setPlaybackToRemote(volumeProvider)

        val stateBuilder = PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE)
            .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1f)
        mediaSession.setPlaybackState(stateBuilder.build())

        mediaSession.isActive = !powerManager.isInteractive
    }

    private fun registerScreenStateReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenStateReceiver, filter)
    }

    // --- Gõ đúp qua accelerometer ---

    private fun startSensorListening() {
        if (sensorListenerActive || accelerometer == null) return
        sensorListenerActive = true
        firstTapTime = 0L
        lastPeakTime = 0L

        // Giữ CPU thức để nhận sự kiện cảm biến kịp thời khi màn hình tắt.
        @Suppress("DEPRECATION")
        partialWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "VolumeWake:DoubleTapListener"
        ).apply { acquire(10 * 60 * 1000L /*tối đa 10 phút, tự release nếu quên*/) }

        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun stopSensorListening() {
        if (!sensorListenerActive) return
        sensorListenerActive = false
        sensorManager.unregisterListener(this)
        partialWakeLock?.let { if (it.isHeld) it.release() }
        partialWakeLock = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt(x * x + y * y + z * z) - SensorManager.GRAVITY_EARTH

        val now = System.currentTimeMillis()

        if (magnitude > tapThreshold && now - lastPeakTime > minGapBetweenPeaksMs) {
            lastPeakTime = now

            if (firstTapTime == 0L) {
                // Đây là cú gõ thứ nhất
                firstTapTime = now
            } else {
                val gap = now - firstTapTime
                if (gap in 1..maxGapForDoubleTapMs) {
                    // Đủ 2 cú gõ trong khoảng thời gian cho phép -> gõ đúp hợp lệ
                    if (now - lastTriggerTime > cooldownAfterTriggerMs) {
                        lastTriggerTime = now
                        wakeUpScreen()
                    }
                    firstTapTime = 0L
                } else {
                    // Quá lâu giữa 2 lần gõ -> tính lại từ cú này
                    firstTapTime = now
                }
            }
        }

        // Nếu chờ quá lâu không có cú gõ thứ 2 thì reset
        if (firstTapTime != 0L && now - firstTapTime > maxGapForDoubleTapMs) {
            firstTapTime = 0L
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun wakeUpScreen() {
        if (!powerManager.isInteractive) {
            @Suppress("DEPRECATION")
            val wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                "VolumeWake:WakeLock"
            )
            wakeLock.acquire(3000)
        }
    }

    private fun startForegroundServiceWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Volume Wake Service", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Volume Wake đang chạy")
            .setContentText("Nhấn Volume hoặc gõ đúp để bật màn hình")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopSensorListening()
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: IllegalArgumentException) {
            // chưa đăng ký, bỏ qua
        }
        if (::mediaSession.isInitialized) {
            mediaSession.isActive = false
            mediaSession.release()
        }
        super.onDestroy()
    }
}
