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

                val autoRotate = Settings.System.getInt(
                    contentResolver,
                    Settings.System.ACCELEROMETER_ROTATION,
                    0
                ) == 1

                if (autoRotate) {
                    hideRotateButton()
                    return
                }

                val candidate = nearestRotation(orientation)
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

                if (now - candidateSince >= 450L && pendingRotation != candidate) {
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

    private fun nearestRotation(orientation: Int): Int = when (orientation) {
        in 315..359, in 0..44 -> Surface.ROTATION_0
        in 45..134 -> Surface.ROTATION_90
        in 135..224 -> Surface.ROTATION_180
        else -> Surface.ROTATION_270
    }

    @Suppress("DEPRECATION")
    private fun currentDisplayRotation(): Int = windowManager.defaultDisplay.rotation

    private fun showRotateButton(targetRotation: Int) {
        handler.removeCallbacks(hideRunnable)

        if (rotateButton == null) {
            rotateButton = ImageButton(this).apply {
                setImageResource(R.drawable.ic_rotate)
                setBackgroundResource(R.drawable.rotate_button_bg)
                contentDescription = "Ekranı döndür"
                elevation = dp(8).toFloat()
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setOnClickListener {
                    val target = pendingRotation ?: return@setOnClickListener
                    rotateTo(target)
                }
            }

            val params = WindowManager.LayoutParams(
                dp(52),
                dp(52),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                x = dp(18)
                y = dp(28)
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
        rotateButton?.animate()?.alpha(1f)?.setDuration(130)?.start()
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

        Settings.System.putInt(
            contentResolver,
            Settings.System.ACCELEROMETER_ROTATION,
            0
        )
        Settings.System.putInt(
            contentResolver,
            Settings.System.USER_ROTATION,
            rotation
        )

        pendingRotation = null
        hideRotateButton()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
