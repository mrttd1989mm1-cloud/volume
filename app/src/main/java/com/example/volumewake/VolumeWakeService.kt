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
 * Service này giữ một MediaSession có thể "đang phát" (PLAYING) để cướp phím
 * Volume vật lý -> dùng để bật màn hình khi đang tắt.
 *
 * ĐIỂM QUAN TRỌNG: session chỉ được set isActive = true khi MÀN HÌNH ĐANG TẮT.
 * Khi màn hình sáng, session bị tắt (isActive = false) để phím Volume hoạt
 * động bình thường (chỉnh nhạc/chuông/media như mặc định), tránh việc app
 * chiếm quyền điều khiển volume suốt ngày.
 */
class VolumeWakeService : Service() {

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var powerManager: PowerManager
    private var currentVolume = 5
    private val maxVolume = 10

    companion object {
        private const val CHANNEL_ID = "volume_wake_channel"
        private const val NOTIFICATION_ID = 1
    }

    // Lắng nghe sự kiện màn hình bật/tắt để bật/tắt MediaSession tương ứng
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    // Màn hình vừa tắt -> kích hoạt session để bắt phím Volume
                    if (::mediaSession.isInitialized) {
                        mediaSession.isActive = true
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    // Màn hình vừa sáng -> tắt session để volume hoạt động bình thường
                    if (::mediaSession.isInitialized) {
                        mediaSession.isActive = false
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        startForegroundServiceWithNotification()
        setupMediaSession()
        registerScreenStateReceiver()
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "VolumeWakeSession")

        val volumeProvider = object : VolumeProviderCompat(
            VOLUME_CONTROL_ABSOLUTE,
            maxVolume,
            currentVolume
        ) {
            override fun onAdjustVolume(direction: Int) {
                // direction = 1 (Volume Up) hoặc -1 (Volume Down)
                if (direction != 0) {
                    wakeUpScreen()
                }
                // Không đổi currentVolume thật -> không ảnh hưởng âm lượng hệ thống
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

        // Chỉ active ngay từ đầu nếu màn hình đang tắt sẵn (trường hợp hiếm khi start service)
        mediaSession.isActive = !powerManager.isInteractive
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
            // Giữ WakeLock 3 giây rồi tự nhả -> màn hình sáng ~3s.
            wakeLock.acquire(3000)
        }
    }

    private fun startForegroundServiceWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Volume Wake Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Volume Wake đang chạy")
            .setContentText("Nhấn nút Volume để bật màn hình")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (e: IllegalArgumentException) {
            // receiver có thể chưa được đăng ký, bỏ qua
        }
        if (::mediaSession.isInitialized) {
            mediaSession.isActive = false
            mediaSession.release()
        }
        super.onDestroy()
    }
}
