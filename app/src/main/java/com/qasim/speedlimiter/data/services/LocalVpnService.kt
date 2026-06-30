package com.qasim.speedlimiter.data.services

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.qasim.speedlimiter.utils.VpnSessionManager
import java.net.DatagramSocket
import java.net.Socket

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
            
            speedLimitKbps = inputLimit.coerceIn(100, 30000)
            
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

    private fun buildTunnel() {
        try { vpnInterface?.close() } catch (e: Exception) {}

        val builder = Builder()
        builder.setSession("SpeedLimiterCorePro")
               .addAddress("10.0.0.2", 32)
               .addRoute("0.0.0.0", 0) // إجبار النظام على تمرير حركة البيانات بالكامل داخل نفق التحديد
               .addDnsServer("8.8.8.8")
               .setMtu(1500)

        // تحديد التطبيقات المستهدفة بالتخنيق
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
            // تشغيل مدير الجلسة لربط تدفق البيانات الحقيقي بخوارزمية التخنيق
            sessionManager.startSession(vpnInterface!!.fileDescriptor, speedLimitKbps, this)
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
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
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
