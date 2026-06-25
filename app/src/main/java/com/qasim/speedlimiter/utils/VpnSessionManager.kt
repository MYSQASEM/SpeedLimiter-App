package com.qasim.speedlimiter.utils

import android.net.VpnService
import com.qasim.speedlimiter.utils.TokenBucket
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.Selector
import java.nio.channels.SelectionKey

class VpnSessionManager(
    private val vpnService: VpnService,
    private val vpnFileDescriptor: FileDescriptor,
    private val tokenBucket: TokenBucket?
) {
    private var isSessionActive = false
    private var selector: Selector? = null

    fun startSession() {
        isSessionActive = true
        
        // تشغيل المحرك في خيط منفصل (Thread) لعدم تعليق التطبيق
        Thread {
            try {
                selector = Selector.open()
                
                val inputStream = FileInputStream(vpnFileDescriptor)
                val outputStream = FileOutputStream(vpnFileDescriptor)
                val buffer = ByteBuffer.allocate(16384)

                // حلقة المعالجة الرئيسية للمحرك
                while (isSessionActive) {
                    val readBytes = inputStream.read(buffer.array())
                    if (readBytes > 0) {
                        
                        // [عصب التقييد] - تخنيق السرعة قبل إرسال أو استقبال أي بايت
                        tokenBucket?.consume(readBytes.toLong())

                        // فك الحزمة وتوجيهها للإنترنت الحقيقي
                        // هنا نقوم بمحاكاة التمرير الآمن عبر حزمة UDP كمثال مستقر ومباشر
                        val tunnelChannel = DatagramChannel.open()
                        vpnService.protect(tunnelChannel.socket()) // حماية الـ Socket من الدوران اللانهائي
                        
                        tunnelChannel.configureBlocking(false)
                        buffer.limit(readBytes)
                        
                        // توجيه البيانات إلى الخادم الوجهة (كمثال سيرفر DNS أو الوجهة المطلوبة)
                        // ملحوظة للمطور لاحقاً: يمكن تحسين هذا السطر لقراءة الـ IP الوجهة ديناميكياً من الحزمة
                        val serverAddress = InetSocketAddress("8.8.8.8", 53)
                        tunnelChannel.send(buffer, serverAddress)
                        
                        // إعادة كتابة البيانات للنظام لتأكيد التمرير
                        buffer.flip()
                        outputStream.write(buffer.array(), 0, readBytes)
                        buffer.clear()
                    }
                    Thread.sleep(1) // راحة للمعالج
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                stopSession()
            }
        }.start()
    }

    fun stopSession() {
        isSessionActive = false
        try { selector?.close() } catch (e: Exception) {}
    }

    fun isSessionRunning(): Boolean {
        return isSessionActive
    }
}
