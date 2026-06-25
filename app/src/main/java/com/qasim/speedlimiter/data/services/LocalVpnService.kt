package com.qasim.speedlimiter.data.services

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.qasim.speedlimiter.utils.TokenBucket
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

class LocalVpnService : VpnService(), Runnable {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private var isRunning = false
    private var speedLimitKbps: Int = 1024
    private var tokenBucket: TokenBucket? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "START") {
            val sharedPrefs = getSharedPreferences("SpeedLimiterPrefs", Context.MODE_PRIVATE)
            speedLimitKbps = sharedPrefs.getInt("speed_limit", 1024)
            
            // حساب السرعة بالبايت في الثانية
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
            // 1. إعداد نفق الـ VPN
            val builder = Builder()
            builder.setSession("SpeedLimiterCore")
                   .addAddress("10.0.0.2", 32)
                   .addRoute("0.0.0.0", 0) 
                   .addDnsServer("8.8.8.8")

            // تحديد التطبيقات المستهدفة (مثل كروم ويوتيوب)
            val targetApps = listOf("com.android.chrome", "com.google.android.youtube")
            for (app in targetApps) {
                try { builder.addAllowedApplication(app) } catch (e: Exception) {}
            }

            vpnInterface = builder.establish() ?: return

            val inputStream = FileInputStream(vpnInterface!!.fileDescriptor)
            val outputStream = FileOutputStream(vpnInterface!!.fileDescriptor)
            
            // 2. إنشاء Socket خارجي حقيقي وتأمينه من الـ Loopback باستخدام protect()
            val tunnelSocket = DatagramSocket()
            protect(tunnelSocket) // <--- هذا السطر يخبر النظام بإرسال البيانات للإنترنت الفعلي خارج الـ VPN

            val buffer = ByteArray(16384)

            while (isRunning) {
                // قراءة الحزم القادمة من التطبيقات (الرفع Upload)
                val readBytes = inputStream.read(buffer)
                if (readBytes > 0) {
                    
                    // ==========================================
                    //  عصب التقييد: كبح سرعة الرفع والتحميل هنا
                    // ==========================================
                    tokenBucket?.consume(readBytes.toLong())

                    // 3. توجيه الحزمة المقيدة إلى الإنترنت الخارجي الفعلي (مثال عبر الـ Socket)
                    // ملاحظة: لتحويل الحزمة بالكامل بشكل مستقر، يفضل إرسالها لسيرفر Proxy محلي أو خارجي
                    try {
                        val packet = DatagramPacket(buffer, readBytes, InetAddress.getByName("8.8.8.8"), 53)
                        tunnelSocket.send(packet)
                    } catch (e: Exception) {
                        // إرسال تجريبي (في الإنتاج الفعلي يتم فك حزمة الـ IP وتوجيهها لعنوانها الأصلي)
                    }

                    // إعادة كتابة الحزم للنظام لاستمرار عمل الاتصال
                    outputStream.write(buffer, 0, readBytes)
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
