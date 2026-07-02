package com.example.volumewake

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var switchService: Switch
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        switchService = findViewById(R.id.switchService)
        tvStatus = findViewById(R.id.tvStatus)

        requestNotificationPermissionIfNeeded()

        switchService.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startVolumeService()
                tvStatus.text = "Đang bật: nhấn nút Volume để đánh thức màn hình"
            } else {
                stopVolumeService()
                tvStatus.text = "Đang tắt"
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        // Android 13+ (API 33) cần xin quyền hiển thị thông báo cho foreground service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100
                )
            }
        }
    }

    private fun startVolumeService() {
        val intent = Intent(this, VolumeWakeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "Đã bật Volume Wake", Toast.LENGTH_SHORT).show()
    }

    private fun stopVolumeService() {
        stopService(Intent(this, VolumeWakeService::class.java))
        Toast.makeText(this, "Đã tắt Volume Wake", Toast.LENGTH_SHORT).show()
    }
}
