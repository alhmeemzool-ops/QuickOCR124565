package com.hamam.quickocr

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Surface
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textview.MaterialTextView
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraActivity : ComponentActivity() {
    private lateinit var preview: PreviewView
    private lateinit var status: MaterialTextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var imageCapture: ImageCapture
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var captureStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        preview = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 10)
        } else startCamera()
    }

    override fun onRequestPermissionsResult(r: Int, p: Array<String>, g: IntArray) {
        super.onRequestPermissionsResult(r, p, g)
        if (r == 10 && g.isNotEmpty() && g[0] == PackageManager.PERMISSION_GRANTED) startCamera()
        else { Toast.makeText(this, "يجب السماح بالكاميرا", Toast.LENGTH_LONG).show(); finish() }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val previewUseCase = androidx.camera.core.Preview.Builder().build()
                previewUseCase.surfaceProvider = preview.surfaceProvider
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetRotation(preview.display?.rotation ?: Surface.ROTATION_0)
                    .build()
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, previewUseCase, imageCapture
                )
                if (!captureStarted) {
                    captureStarted = true
                    preview.postDelayed({ takePhoto() }, 900)
                }
            } catch (e: Exception) {
                showError("تعذر تشغيل الكاميرا")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        status.text = "جاري التقاط الصورة..."
        val photoFile = File(cacheDir, "ocr_photo.jpg")
        val output = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        imageCapture.takePicture(output, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath)
                runOnUiThread { status.text = "جاري استخراج النص..." }
                if (bitmap != null) cameraExecutor.execute { recognize(bitmap) }
                else runOnUiThread {
                    Toast.makeText(this@CameraActivity, "تعذر قراءة الصورة", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            override fun onError(exception: ImageCaptureException) {
                runOnUiThread {
                    Toast.makeText(this@CameraActivity, "تعذر التقاط الصورة", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        })
    }

    private fun recognize(bitmap: Bitmap) {
        try {
            val dataDir = File(filesDir, "tessdata")
            if (!dataDir.exists()) dataDir.mkdirs()
            copyAssetIfMissing(dataDir, "ara.traineddata")
            copyAssetIfMissing(dataDir, "eng.traineddata")
            val tess = TessBaseAPI()
            tess.init(filesDir.absolutePath, "eng+ara", TessBaseAPI.OEM_LSTM_ONLY)
            tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)
            tess.setVariable(
                TessBaseAPI.VAR_CHAR_WHITELIST,
                "0123456789٠١٢٣٤٥٦٧٨٩"
            )
            tess.setImage(bitmap)
            val text = normalizeDigits(tess.getUTF8Text().orEmpty())
            tess.recycle()

            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("OCR", text))

            runOnUiThread {
                progress.progress = 100
                status.text = if (text.isBlank()) "لم يتم العثور على رقم" else "تم نسخ الرقم ✓"
                Toast.makeText(this, if (text.isBlank()) "لم يتم العثور على نص" else "تم النسخ — جاهز للصق", Toast.LENGTH_SHORT).show()
                if (text.isNotBlank()) VolumeAccessibilityService.pasteWhenAvailable(text)
                preview.postDelayed({ finish() }, 250)
            }
        } catch (e: Exception) {
            showError(
                if (e is IOException) "ملفات OCR غير موجودة. أعد بناء التطبيق عبر GitHub Actions"
                else "خطأ OCR: ${e.message ?: "خطأ غير معروف"}"
            )
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun copyAssetIfMissing(dataDir: File, name: String) {
        val target = File(dataDir, name)
        if (!target.exists()) {
            assets.open("tessdata/$name").use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
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

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private fun showError(message: String) {
        runOnUiThread {
            Toast.makeText(this@CameraActivity, message, Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
