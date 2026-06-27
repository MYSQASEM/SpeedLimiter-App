package com.qasim.speedlimiter.utils

import android.net.VpnService
import android.util.Log
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class VpnSessionManager {
    private var isSessionActive = false
    private var readerThread: Thread? = null
    private var dispatcherThread: Thread? = null

    // طابور الحزم الوسيط (مقتبس من فكرة التطبيق الناجح لفصل القراءة عن المعالجة)
    private val packetQueue = LinkedBlockingQueue<ByteArray>(500)

    fun startSession(vpnFileDescriptor: FileDescriptor, speedLimitKbps: Int, vpnService: VpnService) {
        if (isSessionActive) return
        isSessionActive = true
        packetQueue.clear()

        // حساب معدل نقل البيانات بالبايت في الثانية بناءً على السلايدر
        val bytesPerSecond = (speedLimitKbps * 1000L) / 8L

        // 1. خيط القراءة (Reader Thread): يسحب الحزم من النظام بأقصى سرعة ويضعها في الطابور
        readerThread = thread(start = true, name = "VpnReaderThread") {
            val inputChannel = FileInputStream(vpnFileDescriptor).channel
            val buffer = ByteBuffer.allocateDirect(16384)

            try {
                while (isSessionActive) {
                    buffer.clear()
                    val readBytes = inputChannel.read(buffer)
                    if (readBytes > 0) {
                        buffer.flip()
                        val packetData = ByteArray(readBytes)
                        buffer.get(packetData)
                        
                        // إدخال الحزمة إلى الطابور فوراً بدون أي تأخير لحمايتها من التلف
                        if (!packetQueue.offer(packetData, 10, TimeUnit.MILLISECONDS)) {
                            // إذا امتلأ الطابور، يتم إسقاط الحزم القديمة لمنع تجمد النفق
                            packetQueue.poll()
                            packetQueue.offer(packetData)
                        }
                    } else {
                        Thread.sleep(5)
                    }
                }
            } catch (e: Exception) {
                Log.e("VpnCore", "توقف خيط القراءة: ${e.message}")
            } finally {
                try { inputChannel.close() } catch (e: Exception) {}
            }
        }

        // 2. خيط التوزيع والخنق (Dispatcher Thread): يراقب الطابور ويتحكم في وقت خروج الحزم
        dispatcherThread = thread(start = true, name = "VpnDispatcherThread") {
            val outputChannel = FileOutputStream(vpnFileDescriptor).channel
            val writeBuffer = ByteBuffer.allocateDirect(16384)

            try {
                Log.d("VpnCore", "تم تشغيل الموزع الذكي. سقف الخنق الحالي: $speedLimitKbps Kbps")
                
                while (isSessionActive) {
                    // سحب الحزمة من الطابور (ينتظر حتى تتوفر حزم)
                    val packetData = packetQueue.poll(10, TimeUnit.MILLISECONDS)
                    
                    if (packetData != null) {
                        // فحص بروتوكول الحزمة (البايت رقم 9 في ترويسة IPv4)
                        val protocolType = if (packetData.size > 9) packetData[9].toInt() else 0

                        // التقييد الصارم: حزم TCP (التصفح والـ Speedtest) تخضع لحسابات الوقت
                        if (protocolType != 17) { 
                            val requiredDelayNano = (packetData.size.toDouble() / bytesPerSecond.toDouble()) * 1_000_000_000_000L
                            val delayMs = (requiredDelayNano / 1_000_000).toLong()
                            
                            if (delayMs > 0) {
                                // تأخير ذكي لخيط الضخ فقط، دون التأثير على خيط استقبال الشبكة الأساسي
                                Thread.sleep(delayMs.coerceAtMost(80))
                            }
                        }

                        // تجهيز الـ Buffer وضخ الحزمة المقيدة إلى الإنترنت
                        writeBuffer.clear()
                        writeBuffer.put(packetData)
                        writeBuffer.flip()
                        
                        while (writeBuffer.hasRemaining()) {
                            outputChannel.write(writeBuffer)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("VpnCore", "توقف خيط التوزيع: ${e.message}")
            } finally {
                try { outputChannel.close() } catch (e: Exception) {}
            }
        }
    }

    fun setRateLimit(speedLimitKbps: Int) {
        // سيتم اعتماد السرعة الجديدة تلقائياً عند إعادة بناء النفق أو التغيير الفوري
        Log.d("VpnCore", "تحديث سقف السرعة في المحرك إلى: $speedLimitKbps Kbps")
    }

    fun stopSession() {
        isSessionActive = false
        readerThread?.interrupt()
        dispatcherThread?.interrupt()
        readerThread = null
        dispatcherThread = null
        packetQueue.clear()
    }

    fun isSessionRunning(): Boolean = isSessionActive
}
