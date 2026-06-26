package com.qasim.speedlimiter.utils

import android.net.VpnService
import android.util.Log
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
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

        // تحويل السرعة من الكيلوبايت إلى بايتات في الملي ثانية لتتوافق مع خوارزمية التحكم
        val refillRatePerMs = (speedLimitKbps / 8L).coerceAtLeast(1L)
        val maxCapacity = refillRatePerMs * 1000L
        tokenBucket = TokenBucket(maxCapacity, refillRatePerMs)

        workerThread = thread(start = true, name = "VpnTrafficShaperCore") {
            // اقتباس السر: القراءة والكتابة عبر قنوات الواجهة المباشرة (FileChannel) لضمان أقصى أداء للنظام
            val inputChannel = FileInputStream(vpnFileDescriptor).channel
            val outputChannel = FileOutputStream(vpnFileDescriptor).channel
            
            // تخصيص مساحة تخزين مرنة للحزم (ByteBuffer) بحجم حزمة الـ MTU القياسية
            val byteBuffer = ByteBuffer.allocateDirect(16384)

            // سوكيتات محمية لتأمين المخرج الحقيقي للإنترنت دون الدخول في حلقة مفرغة داخل النفق
            val tunnelSocket = Socket()
            val tunnelDatagram = DatagramSocket()
            vpnService.protect(tunnelSocket)
            vpnService.protect(tunnelDatagram)

            try {
                Log.d("SpeedLimiterCore", "تم تشغيل محرك القنوات المطور بنجاح. السقف الحالي: $speedLimitKbps Kbps")
                
                while (isSessionActive) {
                    byteBuffer.clear()
                    val readBytes = inputChannel.read(byteBuffer)
                    
                    if (readBytes > 0) {
                        byteBuffer.flip()

                        // اقتباس السر الثاني: فحص ترويسة الحزمة لمعرفة نوع البروتوكول
                        // البايت رقم 9 في بروتوكول IPv4 يحدد نوع البروتوكول (6 لـ TCP و 17 لـ UDP)
                        val protocolType = byteBuffer.get(9).toInt()

                        if (protocolType == 17) {
                            // حزم UDP (تستخدمها يوتيوب وفيسبوك للمكالمات والفيديو - بروتوكول QUIC)
                            // نمررها فوراً وبسرعة مريحة حتى لا يظن التطبيق أن الإنترنت مقطوع
                            tokenBucket?.consume((readBytes / 2).toLong()) 
                        } else {
                            // حزم TCP (التصفح، التحميل، وقياس السرعة Speedtest)
                            // نخضعها لقفل وخنق صارم بناءً على السلايدر الخاص بك
                            tokenBucket?.consume(readBytes.toLong())
                        }

                        // إعادة كتابة الحزمة المقيدة إلى مخرج النفق الحقيقي بسلاسة
                        while (byteBuffer.hasRemaining()) {
                            outputChannel.write(byteBuffer)
                        }
                    } else {
                        // إذا لم تكن هناك حزم، نريح المعالج بـ 10 ملي ثانية تماماً كما فعل المطور في كود الجافا
                        Thread.sleep(10)
                    }
                }
            } catch (e: Exception) {
                Log.e("SpeedLimiterCore", "خروج آمن من الجلسة المقتبسة: ${e.message}")
            } finally {
                try { inputChannel.close() } catch (e: Exception) {}
                try { outputChannel.close() } catch (e: Exception) {}
                try { tunnelSocket.close() } catch (e: Exception) {}
                try { tunnelDatagram.close() } catch (e: Exception) {}
            }
        }
    }

    fun setRateLimit(speedLimitKbps: Int) {
        val refillRatePerMs = (speedLimitKbps / 8L).coerceAtLeast(1L)
        val maxCapacity = refillRatePerMs * 1000L
        tokenBucket = TokenBucket(maxCapacity, refillRatePerMs)
        Log.d("SpeedLimiterCore", "تحديث فوري لسقف خنق المحرك: $speedLimitKbps Kbps")
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
