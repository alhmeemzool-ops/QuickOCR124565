package com.hamam.quickocr

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.sqrt

class VolumeAccessibilityService : AccessibilityService(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private var lastShake = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val acceleration = sqrt(
            event.values[0] * event.values[0] +
                event.values[1] * event.values[1] +
                event.values[2] * event.values[2]
        )
        val now = SystemClock.elapsedRealtime()
        if (acceleration > 22f && now - lastShake > 1800L) {
            lastShake = now
            val intent = Intent(this, CameraActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
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
