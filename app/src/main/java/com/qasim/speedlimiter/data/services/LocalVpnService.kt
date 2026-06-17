package com.qasim.speedlimiter.data.services

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream

class LocalVpnService : VpnService(), Runnable {
    private var vpnThread: Thread? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private var speedLimitKbps: Int = 1024 

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "START") {
            val sharedPrefs = getSharedPreferences("SpeedLimiterPrefs", Context.MODE_PRIVATE)
            speedLimitKbps = sharedPrefs.getInt("speed_limit", 1024)
            startVpn()
        } else if (action == "STOP") {
            stopVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (vpnThread == null) {
            vpnThread = Thread(this, "LocalVpnThread")
            vpnThread?.start()
        }
    }

    private fun stopVpn() {
        vpnThread?.interrupt()
        vpnThread = null
        try { vpnInterface?.close() } catch (e: Exception) {}
        vpnInterface = null
        stopSelf()
    }

    override fun run() {
        try {
            val builder = Builder()
            
            // إعدادات النفق الذكي المستقر
            builder.setSession("SpeedLimiterPro")
                   .addAddress("10.0.0.2", 32)
                   .addDnsServer("8.8.8.8")
                   .setMtu(1500)

            // لضمان عدم قطع الإنترنت نهائياً، نقوم بالتحكم في حركة بيانات التطبيقات الكبرى فقط
            // بقية النظام والخدمات ستعمل عبر الشبكة الحقيقية مباشرة دون انقطاع
            val appsToLimit = listOf(
                "com.android.chrome",
                "com.google.android.youtube",
                "com.instagram.android",
                "com.zhiliaoapp.musically"
            )

            var hasApp = false
            for (app in appsToLimit) {
                try {
                    builder.addAllowedApplication(app)
                    hasApp = true
                } catch (e: Exception) {}
            }

            // توجيه المسار الداخلي فقط للحزم المحلية لمنع تجميد الشبكة الخارجية
            builder.addRoute("10.0.0.0", 8)

            vpnInterface = builder.establish() ?: return
            
            val inputStream = FileInputStream(vpnInterface!!.fileDescriptor)
            val outputStream = FileOutputStream(vpnInterface!!.fileDescriptor)
            val buffer = ByteArray(16384)

            var bytesSent = 0
            var lastCheck = System.currentTimeMillis()

            while (!Thread.interrupted()) {
                val readBytes = inputStream.read(buffer)
                if (readBytes > 0) {
                    
                    bytesSent += readBytes
                    val now = System.currentTimeMillis()
                    
                    // حساب الحجم الأقصى للبايتات المسموحة في الثانية
                    val maxBytes = (speedLimitKbps * 1024) / 8
                    
                    if (now - lastCheck < 1000) {
                        if (bytesSent >= maxBytes) {
                            val delay = 1000 - (now - lastCheck)
                            if (delay > 0) {
                                // خنق تدفق الحزم عبر النفق بالملي ثانية
                                Thread.sleep(delay) 
                            }
                            bytesSent = 0
                            lastCheck = System.currentTimeMillis()
                        }
                    } else {
                        bytesSent = 0
                        lastCheck = now
                    }

                    // تمرير الحزمة
                    outputStream.write(buffer, 0, readBytes)
                }
                Thread.sleep(2)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            stopVpn()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
    }
}
