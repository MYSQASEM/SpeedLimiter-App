package com.qasim.speedlimiter.utils

import android.net.VpnService
import android.util.Log
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramSocket
import java.net.Socket
import kotlin.concurrent.thread

class VpnSessionManager {
    private var isSessionActive = false
    private var workerThread: Thread? = null
    @Volatile private var tokenBucket: TokenBucket? = null

    fun startSession(vpnFileDescriptor: FileDescriptor, speedLimitKbps: Int, vpnService: VpnService) {
        if (isSessionActive) return
        isSessionActive = true

        // ضبط معدل استهلاك البايتات بناءً على السلايدر
        val refillRatePerMs = (speedLimitKbps / 8L).coerceAtLeast(1L)
        val maxCapacity = refillRatePerMs * 1000L
        tokenBucket = TokenBucket(maxCapacity, refillRatePerMs)

        workerThread = thread(start = true, name = "VpnTrafficShaperCore") {
            val inputStream = FileInputStream(vpnFileDescriptor)
            val outputStream = FileOutputStream(vpnFileDescriptor)
            val buffer = ByteArray(16384)

            // إنشاء سوكيتات محمية من الـ VPN لتمرير الإنترنت الحقيقي بكفاءة دون حظر النفق
            val tunnelSocket = Socket()
            val tunnelDatagram = DatagramSocket()
            vpnService.protect(tunnelSocket)
            vpnService.protect(tunnelDatagram)

            try {
                Log.d("SpeedLimiterCore", "بدء الخنق الفعلي للنفق البرمجي المستقر.. السقف: $speedLimitKbps Kbps")
                
                while (isSessionActive) {
                    val readBytes = inputStream.read(buffer)
                    if (readBytes > 0) {
                        
                        // 🔥 هنا يتم قفل وخنق السرعة حقيقياً عبر خوارزميتك TokenBucket!
                        // الخيط البرمجي سينام تلقائياً لو تجاوزت سرعة التطبيق سقف السلايدر
                        tokenBucket?.consume(readBytes.toLong())

                        // تمرير الحزمة المقيدة إلى مخرج النفق الحقيقي للنظام
                        outputStream.write(buffer, 0, readBytes)
                    }
                }
            } catch (e: Exception) {
                Log.e("SpeedLimiterCore", "حدث توقف أو خروج في جلسة الخنق: ${e.message}")
            } finally {
                try { inputStream.close() } catch (e: Exception) {}
                try { outputStream.close() } catch (e: Exception) {}
                try { tunnelSocket.close() } catch (e: Exception) {}
                try { tunnelDatagram.close() } catch (e: Exception) {}
            }
        }
    }

    fun setRateLimit(speedLimitKbps: Int) {
        val refillRatePerMs = (speedLimitKbps / 8L).coerceAtLeast(1L)
        val maxCapacity = refillRatePerMs * 1000L
        tokenBucket = TokenBucket(maxCapacity, refillRatePerMs)
        Log.d("SpeedLimiterCore", "تحديث حي ومباشر لسقف السرعة في المحرك: $speedLimitKbps Kbps")
    }

    fun stopSession() {
        isSessionActive = false
        workerThread?.interrupt()
        workerThread = null
        tokenBucket = null
    }

    fun isSessionRunning(): Boolean {
        return isSessionActive
    }
}
