package com.qasim.speedlimiter.utils

import android.net.VpnService
import android.util.Log
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.concurrent.thread

class VpnSessionManager {
    private var isSessionActive = false
    private var workerThread: Thread? = null

    fun startSession(vpnFileDescriptor: FileDescriptor, speedLimitKbps: Int, vpnService: VpnService) {
        if (isSessionActive) return
        isSessionActive = true

        workerThread = thread(start = true, name = "VpnTrafficShaperCore") {
            val inputChannel = FileInputStream(vpnFileDescriptor).channel
            val outputChannel = FileOutputStream(vpnFileDescriptor).channel
            val byteBuffer = ByteBuffer.allocateDirect(16384)

            // حساب الوقت المطلوب لكل بايت بناءً على السرعة المحددة
            // السرعة بالبايت في الثانية = (الكيلوبايت * 1000) / 8
            val bytesPerSecond = (speedLimitKbps * 1000L) / 8L

            try {
                Log.d("SpeedLimiterCore", "بدء الخنق الصارم الحقيقي بسقف: $speedLimitKbps Kbps")
                
                while (isSessionActive) {
                    byteBuffer.clear()
                    val readBytes = inputChannel.read(byteBuffer)
                    
                    if (readBytes > 0) {
                        byteBuffer.flip()

                        val protocolType = byteBuffer.get(9).toInt()

                        // الخنق الفعلي: حساب كم من الوقت (بالنانو ثانية) يجب أن تستغرقه هذه الحزمة للمرور
                        // المعادلة: (حجم الحزمة بالبايت / السرعة المسموحة بالبايت) * 1 مليار نانو ثانية
                        if (protocolType != 17) { // التركيز على حزم TCP (المتصفح و Speedtest)
                            val requiredDelayNano = (readBytes.toDouble() / bytesPerSecond.toDouble()) * 1_000_000_000_000L
                            val delayMs = (requiredDelayNano / 1_000_000).toLong()
                            
                            if (delayMs > 0) {
                                // إجبار المعالج والخيط على النوم والانتظار قبل قذف الحزمة للنظام
                                Thread.sleep(delayMs.coerceAtMost(100)) 
                            }
                        }

                        // الآن بعد الانتظار الإجباري، نكتب الحزمة
                        while (byteBuffer.hasRemaining()) {
                            outputChannel.write(byteBuffer)
                        }
                    } else {
                        Thread.sleep(10)
                    }
                }
            } catch (e: Exception) {
                Log.e("SpeedLimiterCore", "خطأ في النفق: ${e.message}")
            } finally {
                try { inputChannel.close() } catch (e: Exception) {}
                try { outputChannel.close() } catch (e: Exception) {}
            }
        }
    }

    fun setRateLimit(speedLimitKbps: Int) {
        // سيتم إعادة تشغيل الجلسة لتطبيق الحسابات الجديدة للسرعة فورا عند التغيير
        Log.d("SpeedLimiterCore", "طلب تعديل السرعة إلى: $speedLimitKbps")
    }

    fun stopSession() {
        isSessionActive = false
        workerThread?.interrupt()
        workerThread = null
    }

    fun isSessionRunning(): Boolean = isSessionActive
}
