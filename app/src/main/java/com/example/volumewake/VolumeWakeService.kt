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
 * Session CHỈ active khi màn hình đang tắt, tự tắt khi màn sáng để phím
 * Volume hoạt động bình thường (chỉnh nhạc/chuông như mặc định).
 *
 * QUAN TRỌNG: session phải được release() sạch sẽ khi service dừng, nếu
 * không hệ thống có thể giữ lại "rác" trong bảng âm lượng (nhiều thanh
 * trượt ảo). Vì vậy:
 * - onStartCommand trả về START_NOT_STICKY: service không tự động được hệ
 *   thống khởi động lại sau khi bị kill -> tránh tạo chồng session mới.
 * - onTaskRemoved: nếu người dùng vuốt tắt app khỏi recents, chủ động dừng
 *   service ngay để giải phóng session thay vì để nó lửng lơ.
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

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    if (::mediaSession.isInitialized) mediaSession.isActive = true
                }
                Intent.ACTION_SCREEN_ON -> {
                    if (::mediaSession.isInitialized) mediaSession.isActive = false
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
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
            .setContentText("Nhấn nút Volume để bật màn hình")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // App bị vuốt tắt khỏi recents -> chủ động dừng service, tránh session lửng lơ
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
        if (::mediaSession.isInitialized) {
            mediaSession.isActive = false
            mediaSession.release()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
