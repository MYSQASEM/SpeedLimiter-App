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
    private var speedLimitKbps: Int = 1024
    
    private var tokenBucket: TokenBucket? = null
    private var sessionManager: VpnSessionManager? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "START") {
            val sharedPrefs = getSharedPreferences("SpeedLimiterPrefs", Context.MODE_PRIVATE)
            speedLimitKbps = sharedPrefs.getInt("speed_limit", 1024)
            
            // حساب السرعة بالبايت في الثانية وتحضير السلة
            val bytesPerSecond = (speedLimitKbps * 1024L) / 8
            val refillRatePerMs = bytesPerSecond / 1000
            
            tokenBucket = TokenBucket(bytesPerSecond, maxOf(1, refillRatePerMs))
            
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
            // إعداد نفق الـ VPN
            val builder = Builder()
            builder.setSession("SpeedLimiterCorePro")
                   .addAddress("10.0.0.2", 32)
                   .addRoute("0.0.0.0", 0) 
                   .addDnsServer("8.8.8.8")

            // تحديد التطبيقات
            val targetApps = listOf("com.android.chrome", "com.google.android.youtube")
            for (app in targetApps) {
                try { builder.addAllowedApplication(app) } catch (e: Exception) {}
            }

            vpnInterface = builder.establish() ?: return

            // تشغيل الـ SessionManager الفعلي الذي يوجه ويقيد السرعة
            sessionManager = VpnSessionManager(
                this, 
                vpnInterface!!.fileDescriptor, 
                tokenBucket
            )
            sessionManager?.startSession()

            // إبقاء الخدمة تعمل طالما الـ VPN مفعل
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
