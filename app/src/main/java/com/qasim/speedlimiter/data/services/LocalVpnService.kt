package com.qasim.speedlimiter.data.services

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress

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
            
            // 🌐 إعداد نفق شفاف وشامل يمنع انقطاع الإنترنت عن أي تطبيق في الهاتف
            builder.setSession("SpeedLimiterPro")
                   .addAddress("192.168.2.2", 24) // تغيير النطاق لنطاق شبكة محلي افتراضي مستقر
                   .addRoute("0.0.0.0", 0)        // تمرير كل حركة المرور عبر النفق للتحكم بها
                   .addDnsServer("8.8.8.8")       // قسر استخدام DNS جوجل العالمي لضمان عمل كافة المواقع والتطبيقات
                   .addDnsServer("1.1.1.1")       // DNS احتياطي لمنع انقطاع التصفح
                   .setMtu(1500)                  // تحديد الحجم القياسي للحزم لمنع التقطيع (MTU 1500)

            vpnInterface = builder.establish() ?: return
            
            val inputStream = FileInputStream(vpnInterface!!.fileDescriptor)
            val outputStream = FileOutputStream(vpnInterface!!.fileDescriptor)
            
            // زيادة حجم البافر ليتناسب مع سرعات الإنترنت العالية ومنع سقوط الحزم
            val buffer = ByteArray(32768) 

            var bytesProcessed = 0
            var lastCheckTime = System.currentTimeMillis()

            // تحويل السرعة المطلوبة من كيلوبت إلى بايتات في الثانية
            // (السرعة بالكيلوبت * 1024) / 8 بايت
            val maxBytesPerSecond = (speedLimitKbps * 1024) / 8

            while (!Thread.interrupted()) {
                val readBytes = inputStream.read(buffer)
                if (readBytes > 0) {
                    
                    bytesProcessed += readBytes
                    val currentTime = System.currentTimeMillis()
                    val timePassed = currentTime - lastCheckTime

                    // ⏱️ لوجيك الخنق الزمني الصارم (Strict Window Throttling)
                    if (timePassed < 1000) {
                        if (bytesProcessed >= maxBytesPerSecond) {
                            val sleepTime = 1000 - timePassed
                            if (sleepTime > 0) {
                                // تجميد النفق بالملي ثانية لإجبار بروتوكول الشبكة على خفض سرعة التنزيل والتحميل فوراً
                                Thread.sleep(sleepTime) 
                            }
                            bytesProcessed = 0
                            lastCheckTime = System.currentTimeMillis()
                        }
                    } else {
                        bytesProcessed = 0
                        lastCheckTime = currentTime
                    }

                    // تمرير البيانات المفرملة بسلام إلى الشبكة
                    outputStream.write(buffer, 0, readBytes)
                }
                // تنظيم دورة المعالج لمنع استهلاك البطارية
                Thread.sleep(1)
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
