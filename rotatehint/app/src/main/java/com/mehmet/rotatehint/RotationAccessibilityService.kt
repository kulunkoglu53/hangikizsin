package com.mehmet.rotatehint

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageButton

class RotationAccessibilityService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private var rotateButton: ImageButton? = null
    private var orientationListener: OrientationEventListener? = null
    private var pendingRotation: Int? = null
    private var lastCandidate: Int? = null
    private var candidateSince = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideRotateButton() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                if (!Settings.System.canWrite(this@RotationAccessibilityService)) {
                    hideRotateButton()
                    return
                }
                if (!isRunningEnabled()) {
                    hideRotateButton()
                    return
                }

                val autoRotate = Settings.System.getInt(
                    contentResolver,
                    Settings.System.ACCELEROMETER_ROTATION,
                    0
                ) == 1

                if (autoRotate) {
                    hideRotateButton()
                    return
                }

                val candidate = calculateStableRotationCandidate(orientation) ?: return
                val current = currentDisplayRotation()

                if (candidate == current) {
                    lastCandidate = candidate
                    pendingRotation = null
                    hideRotateButton()
                    return
                }

                val now = SystemClock.uptimeMillis()
                if (candidate != lastCandidate) {
                    lastCandidate = candidate
                    candidateSince = now
                    return
                }

                if (now - candidateSince >= 400L && pendingRotation != candidate) {
                    pendingRotation = candidate
                    showRotateButton(candidate)
                }
            }
        }

        if (orientationListener?.canDetectOrientation() == true) {
            orientationListener?.enable()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        orientationListener?.disable()
        hideRotateButton()
        super.onDestroy()
    }

    private fun calculateStableRotationCandidate(orientation: Int): Int? {
        return when {
            orientation >= 330 || orientation <= 30 -> Surface.ROTATION_0
            orientation in 60..120 -> Surface.ROTATION_270
            orientation in 150..210 -> Surface.ROTATION_180
            orientation in 240..300 -> Surface.ROTATION_90
            else -> null
        }
    }

    @Suppress("DEPRECATION")
    private fun currentDisplayRotation(): Int = windowManager.defaultDisplay.rotation

    private fun showRotateButton(targetRotation: Int) {
        handler.removeCallbacks(hideRunnable)

        if (rotateButton == null) {
            rotateButton = ImageButton(this).apply {
                setImageResource(R.drawable.ic_overlay_rotate)
                setBackgroundResource(R.drawable.rotate_button_bg)
                contentDescription = "Ekranı döndür"
                elevation = dp(8).toFloat()
                setPadding(dp(9), dp(9), dp(9), dp(9))
                setOnClickListener {
                    val target = pendingRotation ?: return@setOnClickListener
                    rotateTo(target)
                }
            }

            val params = WindowManager.LayoutParams(
                dp(49),
                dp(49),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                x = dp(14)
                y = dp(22)
            }

            try {
                windowManager.addView(rotateButton, params)
            } catch (_: Exception) {
                rotateButton = null
                return
            }
        }

        rotateButton?.visibility = View.VISIBLE
        rotateButton?.alpha = 0f
        rotateButton?.animate()?.alpha(1f)?.setDuration(120)?.start()
        pendingRotation = targetRotation
        handler.postDelayed(hideRunnable, 4500L)
    }

    private fun hideRotateButton() {
        handler.removeCallbacks(hideRunnable)
        val button = rotateButton ?: return
        try {
            windowManager.removeView(button)
        } catch (_: Exception) {
        }
        rotateButton = null
    }

    private fun rotateTo(rotation: Int) {
        if (!Settings.System.canWrite(this)) return

        Settings.System.putInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0)
        Settings.System.putInt(contentResolver, Settings.System.USER_ROTATION, rotation)

        pendingRotation = null
        hideRotateButton()
    }

    private fun isRunningEnabled(): Boolean {
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(MainActivity.PREF_RUNNING, false)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
