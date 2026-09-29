package com.mehmet.rotatehint

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var permissionButton: Button
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
        }

        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_logo_preview)
            contentDescription = getString(R.string.app_name)
        }
        root.addView(logo, LinearLayout.LayoutParams(dp(96), dp(96)).apply {
            bottomMargin = dp(16)
        })

        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 28f
            gravity = Gravity.CENTER
        }
        root.addView(title, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        val info = TextView(this).apply {
            text = "Otomatik döndürme kapalıyken yön değişimini algılar. Sol altta küçük bir döndürme tuşu gösterir. Sadece o tuşa bastığında ekran doğru yöne döner."
            textSize = 16f
            gravity = Gravity.CENTER
        }
        root.addView(info, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(20) })

        status = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })

        permissionButton = Button(this).apply {
            text = "İZİNLERİ AÇ"
            setOnClickListener { openMissingPermissionScreen() }
        }
        root.addView(permissionButton, LinearLayout.LayoutParams(-1, dp(54)).apply {
            bottomMargin = dp(10)
        })

        startButton = Button(this).apply {
            text = "BAŞLAT"
            setOnClickListener {
                if (!hasAllPermissions()) {
                    openMissingPermissionScreen()
                } else {
                    prefs().edit().putBoolean(PREF_RUNNING, true).apply()
                    updateStatus()
                }
            }
        }
        root.addView(startButton, LinearLayout.LayoutParams(-1, dp(56)).apply {
            bottomMargin = dp(10)
        })

        stopButton = Button(this).apply {
            text = "DURDUR"
            setOnClickListener {
                prefs().edit().putBoolean(PREF_RUNNING, false).apply()
                updateStatus()
            }
        }
        root.addView(stopButton, LinearLayout.LayoutParams(-1, dp(52)))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun openMissingPermissionScreen() {
        if (!Settings.System.canWrite(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }
        if (!isAccessibilityEnabled()) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        updateStatus()
    }

    private fun updateStatus() {
        val writeOk = Settings.System.canWrite(this)
        val accessibilityOk = isAccessibilityEnabled()
        val running = prefs().getBoolean(PREF_RUNNING, false)

        val ready = writeOk && accessibilityOk
        permissionButton.visibility = if (ready) View.GONE else View.VISIBLE
        startButton.isEnabled = ready
        stopButton.isEnabled = running

        status.text = when {
            !writeOk && !accessibilityOk -> "İlk kurulum için iki izin gerekli: Sistem ayarlarını değiştirme ve Erişilebilirlik servisi."
            !writeOk -> "Sistem ayarlarını değiştirme izni eksik."
            !accessibilityOk -> "Erişilebilirlik servisini açman gerekiyor."
            running -> "Çalışıyor. Otomatik döndürme kapalıyken yön değiştirince öneri tuşu çıkacak."
            else -> "Hazır. Başlat tuşuna basarak çalıştırabilirsin."
        }
    }

    private fun hasAllPermissions(): Boolean = Settings.System.canWrite(this) && isAccessibilityEnabled()

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, RotationAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        for (service in splitter) {
            if (service.equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    private fun prefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val PREFS_NAME = "rotate_hint_prefs"
        const val PREF_RUNNING = "running"
    }
}
