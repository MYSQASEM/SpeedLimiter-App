package com.qasim.speedlimiter.data.services

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.qasim.speedlimiter.utils.TokenBucket
import com.qasim.speedlimiter.utils.VpnSessionManager

class LocalVpnService : VpnService(), Runnable {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private var isRunning = false
    private var speedLimitKbps: Int = 1024 // القيمة الافتراضية
    
    private var tokenBucket: TokenBucket? = null
    private var sessionManager: VpnSessionManager? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "START") {
            val sharedPrefs = getSharedPreferences("SpeedLimiterPrefs", Context.MODE_PRIVATE)
            
            // استقبال القيمة، وإذا كانت أقل من 100 نثبتها عند 100kbps بناءً على طلبك
            val inputLimit = sharedPrefs.getInt("speed_limit", 1024)
            speedLimitKbps = if (inputLimit < 100) 100 else inputLimit
            
            // الحساب الرياضي الدقيق: تحويل الكيلوبت (Kbps) إلى بايتات فعيلة في الثانية
            // 100 Kbps = (100 * 1000) / 8 = 12,500 Bytes/sec
            val bytesPerSecond = (speedLimitKbps * 1000L) / 8
            val refillRatePerMs = maxOf(1L, bytesPerSecond / 1000L)
            
            // تهيئة السلة بالقيم الدقيقة الجديدة
            tokenBucket = TokenBucket(bytesPerSecond, refillRatePerMs)
            
            if (!isRunning) {
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
                   .addAddress("10.0.0.2", 32)
                   .addRoute("0.0.0.0", 0) 
                   .addDnsServer("8.8.8.8")

            // السماح للتطبيقات الأساسية بالمرور من خلال النفق للمعالجة
            val targetApps = listOf(
                "com.android.chrome", 
                "com.google.android.youtube", 
                "com.facebook.katana",
                "org.zwanoo.android.speedtest" // إضافة حزمة تطبيق سبييد تست للاختبار المباشر
            )
            for (app in targetApps) {
                try { builder.addAllowedApplication(app) } catch (e: Exception) {}
            }

            vpnInterface = builder.establish() ?: return

            sessionManager = VpnSessionManager(
                this, 
                vpnInterface!!.fileDescriptor, 
                tokenBucket
            )
            sessionManager?.startSession()

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
        sessionManager?.stopSession()
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
