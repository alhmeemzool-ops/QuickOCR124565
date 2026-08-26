package com.hamam.quickocr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class QuickCaptureService : LifecycleService() {

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForegroundQuietly()
        startCameraAndCapture()
        return START_NOT_STICKY
    }

    private fun startForegroundQuietly() {
        val channelId = "quick_ocr_capture"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                channelId, "التقاط سريع", NotificationManager.IMPORTANCE_MIN
            ).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Quick OCR")
            .setContentText("جاري القراءة...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(1, notification)
        }
    }

    private fun startCameraAndCapture() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, capture)
                Handler(Looper.getMainLooper()).postDelayed({ takePhoto(capture) }, 350)
            } catch (e: Exception) {
                finishUp()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto(capture: ImageCapture) {
        val photoFile = File(cacheDir, "ocr_photo_bg.jpg")
        val output = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        capture.takePicture(output, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath)
                if (bitmap != null) cameraExecutor.execute { recognize(bitmap) }
                else finishUp()
            }
            override fun onError(exception: ImageCaptureException) {
                finishUp()
            }
        })
    }

    private fun recognize(bitmap: Bitmap) {
        var cropped: Bitmap? = null
        var processed: Bitmap? = null
        try {
            cropped = cropCenter(bitmap)
            processed = toBinaryBlackWhite(cropped)

            val dataDir = File(filesDir, "tessdata")
            if (!dataDir.exists()) dataDir.mkdirs()
            copyAssetIfMissing(dataDir, "ara.traineddata")
            copyAssetIfMissing(dataDir, "eng.traineddata")

            val tess = TessBaseAPI()
            tess.init(filesDir.absolutePath, "eng+ara", TessBaseAPI.OEM_LSTM_ONLY)
            tess.setPageSegMode(7)
            tess.setVariable("tessedit_char_whitelist", "0123456789٠١٢٣٤٥٦٧٨٩")
            tess.setImage(processed)
            val text = normalizeDigits(tess.getUTF8Text().orEmpty())
            tess.recycle()

            if (text.isNotBlank()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("OCR", text))
                VolumeAccessibilityService.pasteWhenAvailable(text)
            }
        } catch (e: Exception) {
            Log.e("QuickCaptureService", "OCR error: ${e.message}")
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
            cropped?.let { if (!it.isRecycled) it.recycle() }
            processed?.let { if (!it.isRecycled) it.recycle() }
            finishUp()
        }
    }

    private fun cropCenter(source: Bitmap): Bitmap {
        val cropWidth = (source.width * 0.55f).toInt()
        val cropHeight = (source.height * 0.28f).toInt()
        val x = ((source.width - cropWidth) / 2).coerceAtLeast(0)
        val y = ((source.height - cropHeight) / 2).coerceAtLeast(0)
        return Bitmap.createBitmap(source, x, y, cropWidth, cropHeight)
    }

    private fun toBinaryBlackWhite(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        var sum = 0L
        for (p in pixels) sum += (Color.red(p) + Color.green(p) + Color.blue(p)) / 3
        val avg = (sum / pixels.size).toInt()
        val threshold = (avg * 0.85f).toInt()

        for (i in pixels.indices) {
            val p = pixels[i]
            val gray = (Color.red(p) + Color.green(p) + Color.blue(p)) / 3
            pixels[i] = if (gray < threshold) Color.BLACK else Color.WHITE
        }
        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }

    private fun copyAssetIfMissing(dataDir: File, name: String) {
        val target = File(dataDir, name)
        if (!target.exists()) {
            assets.open("tessdata/$name").use { input ->
                FileOutputStream(target).use { out -> input.copyTo(out) }
            }
        }
    }

    private fun normalizeDigits(value: String): String =
        value.map {
            when (it) {
                '٠' -> '0'; '١' -> '1'; '٢' -> '2'; '٣' -> '3'; '٤' -> '4'
                '٥' -> '5'; '٦' -> '6'; '٧' -> '7'; '٨' -> '8'; '٩' -> '9'
                else -> it
            }
        }.filter { it in '0'..'9' }.joinToString("")

    private fun finishUp() {
        cameraProvider?.unbindAll()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
