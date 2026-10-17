package com.example.oyuncevir

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class TranslateService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var overlay: TextView? = null
    private lateinit var wm: WindowManager

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.TURKISH)
            .build()
    )

    private var modelReady = false
    private var busy = false
    private var lastWords: Set<String> = emptySet()
    private var emptyCount = 0
    private var width = 0
    private var height = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        startAsForeground()

        val code = intent.getIntExtra("code", 0)
        val data = intent.getParcelableExtra<Intent>("data")
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, handler)

        val dm = resources.displayMetrics
        width = dm.widthPixels
        height = dm.heightPixels
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        display = projection?.createVirtualDisplay(
            "oyuncevir", width, height, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, null
        )

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createOverlay()
        showText("Çeviri modeli hazırlanıyor… (ilk seferde internet gerekir)")

        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener { modelReady = true; showText("Hazır. Oyuna geç.") ; hideLater() }
            .addOnFailureListener { showText("Model indirilemedi, interneti kontrol et.") }

        handler.postDelayed(loop, 800)
        return START_NOT_STICKY
    }

    private val loop = object : Runnable {
        override fun run() {
            if (modelReady && !busy) captureAndTranslate()
            handler.postDelayed(this, 800)
        }
    }

    private fun captureAndTranslate() {
        val image = reader?.acquireLatestImage() ?: return
        val crop: Bitmap
        try {
            val plane = image.planes[0]
            val rowPadding = plane.rowStride - plane.pixelStride * width
            val full = Bitmap.createBitmap(
                width + rowPadding / plane.pixelStride, height, Bitmap.Config.ARGB_8888
            )
            full.copyPixelsFromBuffer(plane.buffer)
            val prefs = getSharedPreferences("p", Context.MODE_PRIVATE)
            val top = (height * prefs.getInt("top", 75) / 100).coerceIn(0, height - 20)
            val bottom = (height * prefs.getInt("bottom", 95) / 100).coerceIn(top + 20, height)
            crop = Bitmap.createBitmap(full, 0, top, width, bottom - top)
            full.recycle()
        } finally {
            image.close()
        }

        busy = true
        recognizer.process(InputImage.fromBitmap(crop, 0))
            .addOnSuccessListener { result ->
                val text = result.text.replace("\n", " ").trim()
                if (text.length < 3) {
                    emptyCount++
                    if (emptyCount >= 3) { overlay?.visibility = View.GONE; lastWords = emptySet() }
                    busy = false
                } else {
                    emptyCount = 0
                    val words = text.lowercase().split(Regex("[^a-z']+")).filter { it.isNotEmpty() }.toSet()
                    if (isSame(words, lastWords)) {
                        busy = false
                    } else {
                        lastWords = words
                        translator.translate(text)
                            .addOnSuccessListener { showText(it); busy = false }
                            .addOnFailureListener { busy = false }
                    }
                }
            }
            .addOnFailureListener { busy = false }
    }

    private fun isSame(a: Set<String>, b: Set<String>): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        val inter = a.intersect(b).size.toFloat()
        val union = a.union(b).size.toFloat()
        return inter / union > 0.8f
    }

    private fun createOverlay() {
        overlay = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xB3000000.toInt())
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(32, 20, 32, 20)
            visibility = View.GONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP }
        wm.addView(overlay, params)
    }

    private fun showText(t: String) {
        val prefs = getSharedPreferences("p", Context.MODE_PRIVATE)
        overlay?.textSize = 16f + prefs.getInt("size", 50) / 100f * 48f
        overlay?.text = t
        overlay?.visibility = View.VISIBLE
    }

    private fun hideLater() {
        handler.postDelayed({ if (lastWords.isEmpty()) overlay?.visibility = View.GONE }, 2500)
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("oc", "Oyun Çeviri", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "oc")
            .setContentTitle("Oyun Çeviri çalışıyor")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        display?.release()
        reader?.close()
        projection?.stop()
        overlay?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        recognizer.close()
        translator.close()
        super.onDestroy()
    }
}
