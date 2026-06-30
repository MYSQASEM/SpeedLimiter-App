package com.qasim.speedlimiter.data.services

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.qasim.speedlimiter.AppConfig
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
            val sharedPrefs = getSharedPreferences(AppConfig.PREFS_NAME, Context.MODE_PRIVATE)
            val inputLimit = sharedPrefs.getInt(AppConfig.KEY_SPEED_LIMIT, 1024)
            
            speedLimitKbps = inputLimit.coerceIn(100, 30000)
            
            if (isRunning) {
                sessionManager.setRateLimit(speedLimitKbps)
                // 🚀 [إعادة بناء النفق ذكياً]: لتحديث قائمة التطبيقات فوراً إذا تم تعديلها أثناء عمل الـ VPN
                buildTunnel()
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
        builder.setSession(AppConfig.VPN_SESSION_NAME)
               .addAddress(AppConfig.VPN_ADDRESS, 32)
               .addRoute(AppConfig.VPN_ROUTE, 0) // إجبار النظام على تمرير حركة البيانات بالكامل داخل نفق التحديد
               .setMtu(AppConfig.VPN_MTU)

        // جلب سيرفرات الـ DNS ديناميكياً
        AppConfig.DNS_SERVERS.forEach { dns ->
            builder.addDnsServer(dns)
        }

        // 🚀 [الربط الديناميكي الجذري]: جلب التطبيقات المحددة بواسطة قاسم من الواجهة
        var targetApps = AppConfig.getTargetApps(this)

        // حماية منطقية: إذا كانت القائمة فارغة تماماً، نقوم بحجز التطبيقات الافتراضية لضمان عمل الخدمة
        if (targetApps.isEmpty()) {
            targetApps = setOf(
                "com.android.chrome", 
                "com.google.android.youtube", 
                "com.facebook.katana",
                "org.zwanoo.android.speedtest"
            )
        }

        for (app in targetApps) {
            try { 
                builder.addAllowedApplication(app) 
                Log.d("LocalVpnService", "تم إدخال التطبيق في جدار الحماية بنجاح: $app")
            } catch (e: Exception) {
                Log.e("LocalVpnService", "التطبيق غير مثبت أو فشل حقنه في النفق: $app")
            }
        }

        try {
            vpnInterface = builder.establish()
            if (vpnInterface != null) {
                // تشغيل مدير الجلسة لربط تدفق البيانات الحقيقي بخوارزمية التخنيق
                sessionManager.startSession(vpnInterface!!.fileDescriptor, speedLimitKbps, this)
                Log.d("LocalVpnService", "تم إنشاء واجهة النفق وتطبيق سقف السرعة بنجاح.")
            }
        } catch (e: Exception) {
            Log.e("LocalVpnService", "فشل استقرار واجهة الـ VPN: ${e.message}")
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
