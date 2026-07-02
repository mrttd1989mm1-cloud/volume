package com.example.volumewake

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
    private lateinit var switchDoubleTap: Switch
    private lateinit var tvStatus: TextView
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(VolumeWakeService.PREFS_NAME, Context.MODE_PRIVATE)

        switchService = findViewById(R.id.switchService)
        switchDoubleTap = findViewById(R.id.switchDoubleTap)
        tvStatus = findViewById(R.id.tvStatus)

        // Khôi phục trạng thái công tắc gõ đúp từ lần trước
        switchDoubleTap.isChecked = prefs.getBoolean(VolumeWakeService.KEY_DOUBLE_TAP, false)

        requestNotificationPermissionIfNeeded()

        switchService.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startVolumeService()
                tvStatus.text = "Đang bật"
            } else {
                switchDoubleTap.isChecked = false
                stopVolumeService()
                tvStatus.text = "Đang tắt"
            }
        }

        switchDoubleTap.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !switchService.isChecked) {
                // Bắt buộc bật service tổng trước
                switchService.isChecked = true
            }
            updateDoubleTapPreference(isChecked)
            tvStatus.text = if (switchService.isChecked) {
                if (isChecked) "Đang bật: Volume + gõ đúp" else "Đang bật: chỉ Volume"
            } else "Đang tắt"
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
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
        val intent = Intent(this, VolumeWakeService::class.java).apply {
            putExtra(VolumeWakeService.EXTRA_DOUBLE_TAP_ENABLED, switchDoubleTap.isChecked)
        }
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

    private fun updateDoubleTapPreference(enabled: Boolean) {
        prefs.edit().putBoolean(VolumeWakeService.KEY_DOUBLE_TAP, enabled).apply()
        if (switchService.isChecked) {
            // Service đang chạy -> báo cho service cập nhật ngay, không cần restart
            val intent = Intent(this, VolumeWakeService::class.java).apply {
                putExtra(VolumeWakeService.EXTRA_DOUBLE_TAP_ENABLED, enabled)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }
}
