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
import android.view.KeyEvent
import androidx.core.app.NotificationCompat

/**
 * Service này chỉ tạo MediaSession MỘT LẦN DUY NHẤT khi service khởi động,
 * và giữ nó sống suốt vòng đời service (không tạo/huỷ theo từng lần tắt/bật
 * màn hình nữa).
 *
 * KHÔNG dùng setPlaybackToRemote()/VolumeProviderCompat vì đó chính là API
 * để vẽ thanh trượt volume tuỳ chỉnh -> đó là nguyên nhân sinh ra nhiều
 * slider. Thay vào đó ta chỉ đăng ký callback để "chặn" (consume) sự kiện
 * phím âm lượng khi màn hình tắt, không khai báo bất kỳ playback/remote
 * volume nào cả -> hệ thống không có lý do gì để vẽ UI volume panel.
 */
class VolumeWakeService : Service() {

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var powerManager: PowerManager

    companion object {
        private const val CHANNEL_ID = "volume_wake_channel"
        private const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        startForegroundServiceWithNotification()
        createPersistentSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // KHÔNG tạo lại session ở đây -> tránh nhân bản
        return START_STICKY
    }

    private fun createPersistentSession() {
        mediaSession = MediaSessionCompat(this, "VolumeWakeSession").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS)

            // Giữ STATE_NONE - không khai báo đang phát media thật,
            // tránh hệ thống coi đây là 1 audio stream cần hiển thị UI
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setState(PlaybackStateCompat.STATE_NONE, 0, 1f)
                    .build()
            )

            setCallback(object : MediaSessionCompat.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(
                        Intent.EXTRA_KEY_EVENT
                    ) ?: return false

                    val isVolumeKey = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN

                    if (isVolumeKey && event.action == KeyEvent.ACTION_DOWN) {
                        if (!powerManager.isInteractive) {
                            wakeUpScreen()
                        }
                        // return true = tự tay xử lý xong, hệ thống KHÔNG
                        // chỉnh volume nữa -> KHÔNG có lý do gì để vẽ panel
                        return true
                    }
                    return false
                }
            })

            // active đúng 1 lần, không toggle theo screen on/off nữa
            isActive = true
        }
    }

    private fun wakeUpScreen() {
        @Suppress("DEPRECATION")
        val wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
            "VolumeWake:WakeLock"
        )
        wakeLock.acquire(3000)
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
        if (::mediaSession.isInitialized) {
            mediaSession.isActive = false
            mediaSession.release()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
