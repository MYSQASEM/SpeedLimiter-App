package com.qasim.speedlimiter.data.services

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.qasim.speedlimiter.utils.TokenBucket
import com.qasim.speedlimiter.utils.VpnSessionManager

class LocalVpnService : VpnService(), Runnable {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    @Volatile private var isRunning = false
    private var speedLimitKbps: Int = 1024
    
    private val sessionManager = VpnSessionManager()

    companion object {
        // محرك السرعة الذكي والمتاح على مستوى الخدمة لربطه مع الـ Session Manager وباقي المحركات
        // القيمة الافتراضية الابتدائية (تُحسب بالبايت: كيلوبايت * 1024)
        val downloadBucket = TokenBucket(1024 * 1024L, 1024 * 1024L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        
        // التقاط التحديثات القادمة من الـ Slider حيةً سواء كانت الخدمة تبدأ أو تعمل بالفعل
        val sharedPrefs = getSharedPreferences("SpeedLimiterPrefs", Context.MODE_PRIVATE)
        val inputLimit = intent?.getIntExtra("speed_limit", sharedPrefs.getInt("speed_limit", 1024)) ?: 1024
        
        speedLimitKbps = inputLimit.coerceIn(100, 30000)
        val limitInBytes = speedLimitKbps * 1024L
        
        // تحديث فوري للمحرك الرياضي لتطبيق السرعة الجديدة وإيقاظ خيوط التوقف الفوري
        downloadBucket.updateRate(limitInBytes, limitInBytes)
        sessionManager.setRateLimit(speedLimitKbps)

        if (action == "START") {
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

    private fun buildTunnel() {
        try { vpnInterface?.close() } catch (e: Exception) {}

        val builder = Builder()
        builder.setSession("SpeedLimiterCorePro")
               .addAddress("10.0.0.2", 24) 
               .addRoute("0.0.0.0", 0)     
               // إضافة أكثر من سيرفر DNS لضمان استقرار المتصفحات وتجنب سقوط حزم الـ TCP
               .addDnsServer("8.8.8.8")
               .addDnsServer("1.1.1.1")
               .setMtu(1500)

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
            // تشغيل الجلسة وتمرير النفق إلى الموزع المطور
            sessionManager.startSession(vpnInterface!!.fileDescriptor, speedLimitKbps, this)
            Log.d("LocalVpnService", "تم إنشاء واجهة الـ VPN وتمرير الجلسة للمحرك بنجاح.")
        } else {
            Log.e("LocalVpnService", "فشل في إنشاء واجهة الـ VPN")
            stopVpn()
        }
    }

    override fun run() {
        try {
            buildTunnel()
            // حلقة الانتظار للمحافظة على خيط الـ VPN مستيقظاً طالما الخدمة تعمل
            while (isRunning) {
                try {
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    break
                }
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
