package com.mehmet.rotatehint

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
        }

        val title = TextView(this).apply {
            text = "Rotate Hint"
            textSize = 28f
            gravity = Gravity.CENTER
        }
        root.addView(title, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })

        val info = TextView(this).apply {
            text = "Otomatik döndürme kapalıyken tabletin yönü değiştiğinde sol altta küçük bir döndürme düğmesi gösterir. Düğmeye dokununca ekran o yöne döner."
            textSize = 16f
            gravity = Gravity.CENTER
        }
        root.addView(info, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(24) })

        status = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })

        val start = Button(this).apply {
            text = "BAŞLAT / İZİNLERİ AÇ"
            setOnClickListener { startSetup() }
        }
        root.addView(start, LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(12) })

        val stop = Button(this).apply {
            text = "DURDUR"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        root.addView(stop, LinearLayout.LayoutParams(-1, dp(52)))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun startSetup() {
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
        status.text = when {
            writeOk && accessibilityOk -> "Hazır. Otomatik döndürmeyi kapalı bırakabilirsin."
            !writeOk && !accessibilityOk -> "İki izin gerekli: Sistem ayarlarını değiştirme + Erişilebilirlik servisi."
            !writeOk -> "Sistem ayarlarını değiştirme izni eksik."
            else -> "Erişilebilirlik servisinin açılması gerekiyor."
        }
    }

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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
