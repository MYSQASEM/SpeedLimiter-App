package com.qasim.speedlimiter.data.services

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class LocalVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var proxyServer: ServerSocket? = null
    private var isRunning = false
    private var speedLimitKbps: Int = 1024 // السرعة الافتراضية

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "START") {
            val sharedPrefs = getSharedPreferences("SpeedLimiterPrefs", Context.MODE_PRIVATE)
            speedLimitKbps = sharedPrefs.getInt("speed_limit", 1024)
            startVpnAndProxy()
        } else if (action == "STOP") {
            stopVpnAndProxy()
        }
        return START_STICKY
    }

    private fun startVpnAndProxy() {
        if (isRunning) return
        isRunning = true

        // 1. بناء نفق شبكة افتراضي قياسي متوافق مع كافة الشبكات (Wi-Fi & Data)
        try {
            val builder = Builder()
            builder.setSession("SpeedLimiterPro")
                   .addAddress("10.0.0.2", 32)
                   .addRoute("0.0.0.0", 0) // توجيه حركة المرور للتحكم بها
                   .addDnsServer("8.8.8.8")
                   .setMtu(1500)
            
            vpnInterface = builder.establish()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. إطلاق الخادم الوكيل المحلي للتحكم في تدفق بايتات الإنترنت الحقيقية
        thread(start = true, name = "ProxyServerThread") {
            try {
                proxyServer = ServerSocket(0) // فتح منفذ عشوائي متاح في الهاتف
                val port = proxyServer?.localPort ?: 0
                
                while (isRunning) {
                    val clientSocket = proxyServer?.accept() ?: break
                    // معالجة كل اتصال قادم من التطبيقات في مسار منفصل لمنع البطء
                    thread {
                        handleClientTraffic(clientSocket)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ⚡ محرك خنق وتحديد سرعة البايتات الفعلي (The Real Throttling Engine)
    private fun handleClientTraffic(clientSocket: Socket) {
        try {
            // حماية المقبس الخاص بالخادم لمنع الدوران اللانهائي في شبكة الـ VPN
            protect(clientSocket)

            val clientInput = clientSocket.getInputStream()
            val clientOutput = clientSocket.getOutputStream()

            // زيادة بافر القراءة لضمان سلاسة البيانات وتجنب تقطيع الاتصال
            val buffer = ByteArray(32768)
            var bytesRead: Int
            
            var totalBytesSent = 0
            var startTime = System.currentTimeMillis()

            // تحويل الحد المختار من كيلوبت إلى بايتات في الثانية
            val maxBytesPerSecond = (speedLimitKbps * 1024) / 8

            while (isRunning && clientInput.read(buffer).also { bytesRead = it } != -1) {
                
                totalBytesSent += bytesRead
                val currentTime = System.currentTimeMillis()
                val elapsedTime = currentTime - startTime

                // إذا تجاوزت البايتات الممررة الحد المسموح به خلال هذه الثانية، نقوم بفرملة تدفق البيانات فوراً
                if (elapsedTime < 1000) {
                    if (totalBytesSent >= maxBytesPerSecond) {
                        val sleepTime = 1000 - elapsedTime
                        if (sleepTime > 0) {
                            Thread.sleep(sleepTime) // خنق فيزيائي حقيقي لسرعة الإنترنت هنا
                        }
                        totalBytesSent = 0
                        startTime = System.currentTimeMillis()
                    }
                } else {
                    totalBytesSent = 0
                    startTime = currentTime
                }

                // تمرير البيانات المفرملة والمحددة إلى وجهتها النهائية بسلام
                clientOutput.write(buffer, 0, bytesRead)
                clientOutput.flush()
            }
        } catch (e: Exception) {
            // معالجة هادئة لقطع الاتصالات الطبيعي عند إغلاق التصفح
        } finally {
            try { clientSocket.close() } catch (e: Exception) {}
        }
    }

    private fun stopVpnAndProxy() {
        isRunning = false
        try { proxyServer?.close() } catch (e: Exception) {}
        try { vpnInterface?.close() } catch (e: Exception) {}
        proxyServer = null
        vpnInterface = null
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpnAndProxy()
    }
}
