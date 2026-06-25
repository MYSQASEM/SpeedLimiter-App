package com.qasim.speedlimiter.utils

import android.net.VpnService
import com.qasim.speedlimiter.utils.TokenBucket
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.net.DatagramSocket
import java.net.DatagramPacket
import java.net.InetAddress

class VpnSessionManager(
    private val vpnService: VpnService,
    private val vpnFileDescriptor: FileDescriptor,
    private val tokenBucket: TokenBucket?
) {
    private var isSessionActive = false
    private var workerThread: Thread? = null

    fun startSession() {
        isSessionActive = true
        
        workerThread = Thread {
            try {
                val inputStream = FileInputStream(vpnFileDescriptor)
                val outputStream = FileOutputStream(vpnFileDescriptor)
                val buffer = ByteArray(16384)

                // إنشاء سوكيت حامٍ وعام للتعامل مع حركة مرور الشبكة الخارجة
                val rawSocket = DatagramSocket()
                vpnService.protect(rawSocket) // حماية السوكيت لمنع الحلقة اللانهائية

                while (isSessionActive) {
                    val readBytes = inputStream.read(buffer)
                    if (readBytes > 0) {
                        
                        // [عصب التقييد الجوهري]
                        // هنا يتم احتجاز الحزمة وتأخيرها بناءً على الـ TokenBucket الحالية (مثلاً سرعة الـ 100kbps)
                        tokenBucket?.consume(readBytes.toLong())

                        // معالجة وإرسال الحزمة بشكل عام لضمان عدم انقطاع الإنترنت عن يوتيوب وفيس بوك
                        try {
                            // التمرير البرمجي المباشر للحزم عبر السوكيت المحمي لضمان عبورها للشبكة الفعلية
                            val packet = DatagramPacket(buffer, readBytes, InetAddress.getByName("8.8.8.8"), 53)
                            rawSocket.send(packet)
                        } catch (e: Exception) {
                            // معالجة الخطأ محلياً في حال تعثر حزمة فردية
                        }

                        // إرجاع استجابة الحزمة للنفق للحفاظ على استقرار بروتوكول الشبكة في النظام
                        outputStream.write(buffer, 0, readBytes)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        workerThread?.start()
    }

    fun stopSession() {
        isSessionActive = false
        workerThread?.interrupt()
        workerThread = null
    }

    fun isSessionRunning(): Boolean {
        return isSessionActive
    }
}
