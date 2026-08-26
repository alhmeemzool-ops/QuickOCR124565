package com.hamam.quickocr

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

class VolumeAccessibilityService : AccessibilityService(), SensorEventListener {
    private lateinit var sensorManager: SensorManager

    private val gravity = FloatArray(3)
    private var lastSign = 0
    private var crossCount = 0
    private var lastCrossTime = 0L
    private var lastTrigger = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val alpha = 0.8f
        gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0]
        gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1]
        gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2]

        val x = event.values[0] - gravity[0]
        val y = event.values[1] - gravity[1]
        val z = event.values[2] - gravity[2]

        val now = SystemClock.elapsedRealtime()

        if (now - lastCrossTime > 900L) {
            crossCount = 0
            lastSign = 0
        }

        val horizontalDominant = abs(x) > abs(y) * 1.3f && abs(x) > abs(z) * 1.3f
        if (horizontalDominant && abs(x) > 7f) {
            val sign = if (x > 0) 1 else -1
            if (sign != lastSign) {
                lastSign = sign
                crossCount++
                lastCrossTime = now
            }
        }

        if (crossCount >= 4 && now - lastTrigger > 2500L) {
            crossCount = 0
            lastSign = 0
            lastTrigger = now
            triggerCapture()
        }
    }

    private fun triggerCapture() {
        val intent = Intent(this, QuickCaptureService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        if (::sensorManager.isInitialized) sensorManager.unregisterListener(this)
        instance = null
        super.onDestroy()
    }

    fun pasteIntoFocusedField(text: String) {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val inserted = node?.isEditable == true &&
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            })

        if (!inserted) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("OCR", text))
        }
    }

    companion object {
        @Volatile private var instance: VolumeAccessibilityService? = null

        fun pasteWhenAvailable(text: String) {
            instance?.let { service ->
                service.handler.postDelayed({ service.pasteIntoFocusedField(text) }, 350L)
            }
        }
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    init {
        instance = this
    }
}
