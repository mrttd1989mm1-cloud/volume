package com.example.volumewake

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.VolumeProviderCompat

/**
 * Service này giữ một MediaSession luôn ở trạng thái "đang phát" (PLAYING).
 * Khi một MediaSession đang active + playing, hệ điều hành Android sẽ định
 * tuyến sự kiện phím Volume vật lý vào VolumeProvider của session đó
 * (thay vì chỉnh âm lượng chuông/media mặc định), kể cả khi màn hình đang tắt,
 * miễn là service (và do đó session) vẫn còn sống.
 *
 * Mỗi lần callback onAdjustVolume() được gọi tức là người dùng vừa bấm phím
 * Volume Up/Down -> ta xin WakeLock để bật màn hình trong vài giây.
 */
class VolumeWakeService : Service() {

    private lateinit var mediaSession: MediaSessionCompat
    private var currentVolume = 5
    private val maxVolume = 10

    companion object {
        private const val CHANNEL_ID = "volume_wake_channel"
        private const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceWithNotification()
        setupMediaSession()
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

        mediaSession.isActive = true
    }

    private fun wakeUpScreen() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
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
        if (::mediaSession.isInitialized) {
            mediaSession.isActive = false
            mediaSession.release()
        }
        super.onDestroy()
    }
}
