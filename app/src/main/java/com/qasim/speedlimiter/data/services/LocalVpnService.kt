package com.qasim.speedlimiter.data.services

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.qasim.speedlimiter.AppConfig // استيراد ملف الإعدادات الجديد
import com.qasim.speedlimiter.utils.TokenBucket
import com.qasim.speedlimiter.utils.VpnSessionManager

class LocalVpnService : VpnService(), Runnable {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    @Volatile private var isRunning = false
    private var speedLimitKbps: Int = 1024
    
    private val sessionManager = VpnSessionManager()

    companion object {
        // 🚀 [الإصلاح الذكي]: إضافة الـ instance لتمكين المحركات الأخرى من الوصول للخدمة وحماية السوكتات
        @Volatile var instance: LocalVpnService? = null

        // محرك السرعة الذكي والمتاح على مستوى الخدمة لربطه مع الـ Session Manager وباقي المحركات
        val downloadBucket = TokenBucket(1024 * 1024L, 1024 * 1024L)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this // تعيين الـ instance عند إنشاء الخدمة
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this // لضمان بقاء المرجع نشطاً دائماً عند تحديث السلايدر
        
        val action = intent?.action
        
        // التقاط التحديثات القادمة من الـ Slider حيةً سواء كانت الخدمة تبدأ أو تعمل بالفعل
        val sharedPrefs = getSharedPreferences("SpeedLimiterPrefs", Context.MODE_PRIVATE)
        val inputLimit = intent?.getIntExtra("speed_limit", sharedPrefs.getInt("speed_limit", 1024)) ?: 1024
        
        // استخدام الحدود الدنيا والعليا ديناميكياً من ملف AppConfig
        speedLimitKbps = inputLimit.coerceIn(AppConfig.MIN_SPEED_LIMIT, AppConfig.MAX_SPEED_LIMIT)
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
        
        // قراءة إعدادات الشبكة ديناميكياً من ملف AppConfig المطور
        builder.setSession(AppConfig.VPN_SESSION_NAME)
               .addAddress(AppConfig.VPN_ADDRESS, 24) 
               .addRoute(AppConfig.VPN_ROUTE, 0)     
               .setMtu(AppConfig.VPN_MTU)

        // إضافة سيرفرات الـ DNS بشكل ديناميكي مكرر من الـ AppConfig لضمان ثبات التصفح والـ TCP
        AppConfig.DNS_SERVERS.forEach { dns ->
            builder.addDnsServer(dns)
        }

        // إضافة التطبيقات المستهدفة بالخنق ديناميكياً بلف حلقة تكرار حول القائمة في AppConfig
        AppConfig.TARGET_APPLICATIONS.forEach { appPackage ->
            try { 
                builder.addAllowedApplication(appPackage) 
            } catch (e: Exception) {
                Log.e("LocalVpnService", "التطبيق غير مثبت على هذا الهاتف: $appPackage")
            }
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
        instance = null // تنظيف المرجع عند إيقاف الخدمة
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
    }
}
