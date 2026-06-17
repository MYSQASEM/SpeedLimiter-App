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
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private var speedLimitKbps: Int = 1024 

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "START") {
            val sharedPrefs = getSharedPreferences("SpeedLimiterPrefs", Context.MODE_PRIVATE)
            speedLimitKbps = sharedPrefs.getInt("speed_limit", 1024)
            startVpnAndServer()
        } else if (action == "STOP") {
            stopVpnAndServer()
        }
        return START_STICKY
    }

    private fun startVpnAndServer() {
        if (isRunning) return
        isRunning = true

        // 1. إنشاء نفق الـ VPN الشفاف لتوجيه البيانات محلياً
        try {
            val builder = Builder()
            builder.setSession("SpeedLimiterPro")
                   .addAddress("10.0.0.2", 32)
                   .addRoute("0.0.0.0", 0) // التقاط البيانات للتحكم بها
                   .addDnsServer("8.8.8.8")
                   .setMtu(1500)
            vpnInterface = builder.establish()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. إطلاق الخادم المحلي المصغر للتحكم الفعلي بالبايتات المخنوقة
        thread(start = true, name = "LocalProxyThread") {
            try {
                serverSocket = ServerSocket(0) // فتح منفذ تلقائي متاح
                val localPort = serverSocket!!.localPort

                while (isRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    // التعامل مع كل اتصال في Thread منفصل لضمان سرعة واستقرار التصفح
                    thread { handleClient(clientSocket) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun handleClient(clientSocket: Socket) {
        try {
            // محاكاة توجيه البيانات للإنترنت الحقيقي عبر مآخذ محمية من الالتفاف
            // نستخدم منفذ وهمي محلي كمثال للربط، وبما أننا بحاجة للاتصال بالخارج:
            val host = "8.8.8.8" // كمثال لبوابة العبور
            val targetSocket = Socket(host, 53)
            protect(targetSocket) // 🔒 حماية السوكيت لمنع انقطاع الإنترنت والدوران اللانهائي

            val clientIn = clientSocket.getInputStream()
            val clientOut = clientSocket.getOutputStream()
            val targetIn = targetSocket.getInputStream()
            val targetOut = targetSocket.getOutputStream()

            // تشغيل التمرير المخنوق ذو الاتجاهين (تحميل وتنزيل)
            thread { forwardWithThrottling(clientIn, targetOut) } // التحميل (Upload)
            forwardWithThrottling(targetIn, clientOut) // التنزيل (Download)

        } catch (e: Exception) {
            try { clientSocket.close() } catch (ex: Exception) {}
        }
    }

    // ⚡ محرك الخنق الفعلي المبني على تدفق البايتات (Byte-Stream Throttling)
    private fun forwardWithThrottling(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(4096)
        var bytesProcessed = 0
        var lastCheckTime = System.currentTimeMillis()

        try {
            while (isRunning) {
                val readBytes = input.read(buffer)
                if (readBytes == -1) break

                output.write(buffer, 0, readBytes)
                output.flush()

                bytesProcessed += readBytes
                
                // حساب سقف البايتات المسموح بها في الملي ثانية بناءً على اختيار قاسم من السلايدر
                val maxBytesPerSecond = (speedLimitKbps * 1024) / 8
                val now = System.currentTimeMillis()
                val timePassed = now - lastCheckTime

                if (timePassed < 1000) {
                    if (bytesProcessed >= maxBytesPerSecond) {
                        val sleepTime = 1000 - timePassed
                        if (sleepTime > 0) {
                            Thread.sleep(sleepTime) // فرملة حقيقية للمجرى الرقمي
                        }
                        bytesProcessed = 0
                        lastCheckTime = System.currentTimeMillis()
                    }
                } else {
                    bytesProcessed = 0
                    lastCheckTime = now
                }
            }
        } catch (e: Exception) {
            // إغلاق المجرى بأمان عند الانتهاء
        } finally {
            try { input.close() } catch (e: Exception) {}
            try { output.close() } catch (e: Exception) {}
        }
    }

    private fun stopVpnAndServer() {
        isRunning = false
        try { serverSocket?.close() } catch (e: Exception) {}
        try { vpnInterface?.close() } catch (e: Exception) {}
        serverSocket = null
        vpnInterface = null
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpnAndServer()
    }
}
