package com.example.oyuncevir

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val reqCapture = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("p", Context.MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        root.addView(TextView(this).apply {
            text = "Oyun Çeviri\n\nBaşlat'a bas, sonra PS Remote Play'e geç. " +
                "Çeviri ekranın ÜSTÜNDE görünür. Altyazı alanı (aşağıdaki ayar) çevirinin altında kalmalı."
            textSize = 16f
        })

        fun seek(label: String, key: String, def: Int) {
            val tv = TextView(this).apply { text = "$label: ${prefs.getInt(key, def)}%" }
            val sb = SeekBar(this).apply { max = 100; progress = prefs.getInt(key, def) }
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    prefs.edit().putInt(key, p).apply()
                    tv.text = "$label: $p%"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            root.addView(tv)
            root.addView(sb)
        }
        seek("Altyazı alanı başlangıcı (yukarıdan)", "top", 75)
        seek("Altyazı alanı bitişi", "bottom", 95)

        root.addView(Button(this).apply {
            text = "Başlat"
            setOnClickListener { start() }
        })
        root.addView(Button(this).apply {
            text = "Durdur"
            setOnClickListener { stopService(Intent(this@MainActivity, TranslateService::class.java)) }
        })
        setContentView(root)
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Önce 'diğer uygulamaların üzerinde göster' iznini ver", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqCapture && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, TranslateService::class.java)
                .putExtra("code", resultCode)
                .putExtra("data", data)
            startForegroundService(i)
        }
    }
}
