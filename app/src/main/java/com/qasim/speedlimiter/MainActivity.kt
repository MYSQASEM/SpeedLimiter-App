package com.qasim.speedlimiter

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
// السطر السحري لحل المشكلة الأولى: استيراد الـ VPN من مساره الجديد
import com.qasim.speedlimiter.data.services.LocalVpnService

class MainActivity : AppCompatActivity() {

    private var speedLimit = 50 
    private val vpnRequestCode = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val speedSeekBar = findViewById<SeekBar>(R.id.speedSeekBar)
        val currentSpeedText = findViewById<TextView>(R.id.currentSpeedText)
        val btnToggleVpn = findViewById<Button>(R.id.btnToggleVpn)

        speedSeekBar.max = 100
        speedSeekBar.progress = speedLimit
        currentSpeedText.text = "السرعة الحالية: $speedLimit KB/s"

        speedSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                var currentProgress = progress
                if (currentProgress < 1) currentProgress = 1 
                speedLimit = currentProgress
                currentSpeedText.text = "السرعة الحالية: $speedLimit KB/s"
                
                val intent = Intent(this@MainActivity, LocalVpnService::class.java).apply {
                    action = "UPDATE_SPEED"
                    putExtra("SPEED_LIMIT", speedLimit)
                }
                startService(intent)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnToggleVpn.setOnClickListener {
            val intent = VpnService.prepare(this)
            if (intent != null) {
                startActivityForResult(intent, vpnRequestCode)
            } else {
                onActivityResult(vpnRequestCode, RESULT_OK, null)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == vpnRequestCode && resultCode == RESULT_OK) {
            val intent = Intent(this, LocalVpnService::class.java).apply {
                action = "START_VPN"
                putExtra("SPEED_LIMIT", speedLimit)
            }
            startService(intent)
            Toast.makeText(this, "تم تفعيل فرملة السرعة بنجاح!", Toast.LENGTH_SHORT).show()
        }
    }
}
