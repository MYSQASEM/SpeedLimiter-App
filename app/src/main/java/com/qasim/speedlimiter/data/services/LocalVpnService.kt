package com.qasim.speedlimiter.data.services

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.qasim.speedlimiter.utils.VpnSessionManager

class LocalVpnService : VpnService(), Runnable {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    @Volatile private var isRunning = false
    private var speedLimitKbps: Int = 1024
    
    private val sessionManager = VpnSessionManager()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "START") {
            val sharedPrefs = getSharedPreferences("SpeedLimiterPrefs", Context.MODE_PRIVATE)
            val inputLimit = sharedPrefs.getInt("speed_limit", 1024)
            
            // ضبط الحدود البرمجية الصارمة: من 100 Kbps كحد أدنى إلى 30000 Kbps (أي 30 Mbps) كحد أقصى
            speedLimitKbps = inputLimit.coerceIn(100, 30000)
            
            if (isRunning) {
                sessionManager.setRateLimit(speedLimitKbps)
                updateVpnTunnel()
            } else {
                isRunning = true
                vpnThread = Thread(this, "SpeedVpnThread")
                vpnThread?.start()
            }
        } else if (action == "STOP") {
            stopVpn()
        }
        return START_STICKY
    }

    private fun updateVpnTunnel() {
        Thread {
            try {
                buildTunnel()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun buildTunnel() {
        try { vpnInterface?.close() } catch (e: Exception) {}

        val builder = Builder()
        builder.setSession("SpeedLimiterCorePro")
               .addAddress("10.0.0.2", 32)

        // 📊 معادلة ضبط الـ MTU الحسابية الموزونة لضمان التوازن بين الرفع والتنزيل:
        // قمنا برفع الحد الأدنى الحرج إلى 576 لمنع اختناق حزم الرفع (Upload) وضمان منطقية القراءات
        val calculatedMtu = (576 + (speedLimitKbps / 30)).coerceIn(576, 1500)
        builder.setMtu(calculatedMtu)

        Log.d("SpeedLimiterCore", "تطبيق التخنيق المتزن.. السرعة المحددة: $speedLimitKbps Kbps، الـ MTU الناتجة: $calculatedMtu")

        val targetApps = listOf(
            "com.android.chrome", 
            "com.google.android.youtube", 
            "com.facebook.katana",
            "org.zwanoo.android.speedtest"
        )
        for (app in targetApps) {
            try { builder.addAllowedApplication(app) } catch (e: Exception) {}
        }

        vpnInterface = builder.establish()
        
        if (vpnInterface != null) {
            sessionManager.startSession(vpnInterface!!.fileDescriptor, speedLimitKbps)
        }
    }

    override fun run() {
        try {
            buildTunnel()
            while (isRunning) {
                Thread.sleep(1000)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            stopVpn()
        }
    }

    private fun stopVpn() {
        isRunning = false
        sessionManager.stopSession()
        vpnThread?.interrupt()
        vpnThread = null
        try { vpnInterface?.close() } catch (e: Exception) {}
        vpnInterface = null
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
    }
}
