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
            
            speedLimitKbps = if (inputLimit < 100) 100 else inputLimit
            
            if (isRunning) {
                sessionManager.setRateLimit(speedLimitKbps)
                // إعادة تشغيل النفق لتطبيق الـ MTU الجديد فوراً عند تحريك السلايدر
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
        // إغلاق النفق القديم قبل بناء الجديد لتفادي تجميد الاتصال
        try { vpnInterface?.close() } catch (e: Exception) {}

        val builder = Builder()
        builder.setSession("SpeedLimiterCorePro")
               .addAddress("10.0.0.2", 32) // تحديد الآيبي بشكل مخصص لتجنب تعليق الإنترنت

        // 🧠 خوارزمية الخنق الذكي عبر الـ MTU:
        // الحساب الرياضي يقوم بتصغير الحزم عند خفض السرعة لإجبار النظام على إبطاء نقل البيانات
        val calculatedMtu = (100 + (speedLimitKbps / 4)).coerceIn(128, 1500)
        builder.setMtu(calculatedMtu)

        Log.d("SpeedLimiterCore", "جاري تطبيق التحديد الحديدي بالنظام.. السرعة: $speedLimitKbps, الـ MTU: $calculatedMtu")

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
