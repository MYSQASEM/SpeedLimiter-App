package com.qasim.speedlimiter.data.services

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.qasim.speedlimiter.utils.VpnSessionManager

class LocalVpnService : VpnService(), Runnable {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private var isRunning = false
    private var speedLimitKbps: Int = 1024
    
    // تهيئة مدير الجلسة الجديد المستقل (بدون تمرير Context)
    private val sessionManager = VpnSessionManager()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "START") {
            val sharedPrefs = getSharedPreferences("SpeedLimiterPrefs", Context.MODE_PRIVATE)
            val inputLimit = sharedPrefs.getInt("speed_limit", 1024)
            
            // تطبيق شرط الـ 100kbps كحد أدنى بناءً على طلبك
            speedLimitKbps = if (inputLimit < 100) 100 else inputLimit
            
            if (isRunning) {
                sessionManager.setRateLimit(speedLimitKbps)
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

    override fun run() {
        try {
            val builder = Builder()
            builder.setSession("SpeedLimiterCorePro")
                   .addAddress("10.0.0.2", 24) 
                   .addRoute("0.0.0.0", 0) 
                   .addDnsServer("8.8.8.8")
                   .setMtu(1500)

            // تحديد الحزم لتمرير الإنترنت لكل التطبيقات الأساسية التي فحصتها
            val targetApps = listOf(
                "com.android.chrome", 
                "com.google.android.youtube", 
                "com.facebook.katana",
                "org.zwanoo.android.speedtest"
            )
            for (app in targetApps) {
                try { builder.addAllowedApplication(app) } catch (e: Exception) {}
            }

            vpnInterface = builder.establish() ?: return

            // =======================================================
            // [التعديل المقصود] تمرير الـ fileDescriptor مباشرة للكود الجديد
            // =======================================================
            sessionManager.startSession(vpnInterface!!.fileDescriptor, speedLimitKbps)

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
