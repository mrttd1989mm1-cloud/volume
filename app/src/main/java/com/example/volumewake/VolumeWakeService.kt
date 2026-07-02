package com.example.volumewake

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.VolumeProviderCompat

/**
 * Service này CHỈ tạo MediaSession khi màn hình đang tắt, và HUỶ HẲN
 * (release) session đó ngay khi màn hình sáng lại -> không để bất kỳ session
 * nào tồn tại trong bộ nhớ hệ thống lúc màn sáng, tránh việc một số bảng âm
 * lượng của hãng máy (One UI, MIUI...) vẫn hiện slider dù isActive = false.
 */
class VolumeWakeService : Service() {

    private var mediaSession: MediaSessionCompat? = null
    private lateinit var powerManager: PowerManager
    private var currentVolume = 5
    private val maxVolume = 10

    companion object {
        private const val CHANNEL_ID = "volume_wake_channel"
        private const val NOTIFICATION_ID = 1
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> createSessionIfNeeded()
                Intent.ACTION_SCREEN_ON -> destroySessionIfExists()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        startForegroundServiceWithNotification()
        registerScreenStateReceiver()

        // Nếu service khởi động ngay lúc màn hình đang tắt sẵn
        if (!powerManager.isInteractive) {
            createSessionIfNeeded()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    private fun createSessionIfNeeded() {
        if (mediaSession != null) return

        val session = MediaSessionCompat(this, "VolumeWakeSession")

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

        session.setPlaybackToRemote(volumeProvider)

        val stateBuilder = PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE)
            .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1f)
        session.setPlaybackState(stateBuilder.build())

        session.isActive = true
        mediaSession = session
    }

    private fun destroySessionIfExists() {
        mediaSession?.let {
            it.isActive = false
            it.release()
        }
        mediaSession = null
    }

    private fun registerScreenStateReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenStateReceiver, filter)
    }

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
                CHANNEL_ID, "Volume Wake Service", NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Volume Wake")
            .setContentText("Đang chạy nền")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        cleanupAndStop()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        cleanupAndStop()
        super.onDestroy()
    }

    private fun cleanupAndStop() {
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: IllegalArgumentException) {
            // chưa đăng ký, bỏ qua
        }
        destroySessionIfExists()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
