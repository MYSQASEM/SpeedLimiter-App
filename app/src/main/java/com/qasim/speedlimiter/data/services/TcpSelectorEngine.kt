package com.qasim.speedlimiter.data.services

import android.util.Log
import com.qasim.speedlimiter.utils.VpnConnectionSession
import com.qasim.speedlimiter.utils.TokenBucket
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.BlockingQueue

/**
 * محرك معالجة البيانات واستقبالها من الإنترنت الحقيقي (مستوحى ومطور من h.java في كود المطور)
 * متواجد في حزمة الخدمات (services) ويعمل في خيط منفصل لإدارة الاتصالات وتأخير حزم التحميل (Download)
 */
class TcpSelectorEngine(
    private val selector: Selector,
    private val outputQueue: BlockingQueue<ByteBuffer> // الطابور الموجه لإعادة ضخ الحزم بالنفق
) : Runnable {

    override fun run() {
        Log.d("TcpSelectorEngine", "بدأ خيط معالجة السوكتات والاستقبال الفعلي بالعمل...")
        
        while (!Thread.interrupted()) {
            try {
                // الانتظار حتى تصبح إحدى القنوات جاهزة للاستقبال أو الاتصال
                if (selector.select() == 0) {
                    Thread.sleep(10)
                    continue
                }

                val selectedKeys = selector.selectedKeys()
                val iterator = selectedKeys.iterator()

                while (iterator.hasNext() && !Thread.interrupted()) {
                    val key = iterator.next()
                    
                    if (key.isValid) {
                        if (key.isConnectable) {
                            handleConnect(key, iterator)
                        } else if (key.isReadable) {
                            handleRead(key, iterator)
                        }
                    }
                }
            } catch (e: Exception) {
                if (Thread.interrupted()) break
                Log.e("TcpSelectorEngine", "خطأ في حلقة الـ Selector الرئيسي: ${e.message}")
            }
        }
    }

    /**
     * معالجة إتمام الاتصال بنجاح مع السيرفر الخارجي خارج الـ VPN
     */
    private fun handleConnect(key: SelectionKey, iterator: MutableIterator<SelectionKey>) {
        val session = key.attachment() as? VpnConnectionSession ?: return
        val channel = key.channel() as SocketChannel
        
        try {
            if (channel.finishConnect()) {
                iterator.remove()
                session.connectionState = 2 // متصل الآن بنجاح
                // تحويل القناة لتصبح جاهزة للقراءة والاستماع فقط
                key.interestOps(SelectionKey.OP_READ)
                Log.d("TcpSelectorEngine", "تم الاتصال بنجاح بالسيرفر الخارجي للجلسة: ${session.sessionKey}")
            }
        } catch (e: IOException) {
            iterator.remove()
            Log.e("TcpSelectorEngine", "فشل إتمام الاتصال بالسيرفر: ${e.message}")
            VpnConnectionSession.closeSession(session)
        }
    }

    /**
     * قراءة بايتات التحميل وتطبيق الخنق بالملي ثانية عبر الـ TokenBucket الخاص بالخدمة
     */
    private fun handleRead(key: SelectionKey, iterator: MutableIterator<SelectionKey>) {
        val session = key.attachment() as? VpnConnectionSession ?: return
        val channel = key.channel() as SocketChannel
        
        // حجز بايت بفر مخصص لقراءة الحزمة (مساحة كافية لحزمة TCP القياسية)
        val buffer = ByteBuffer.allocate(16384)
        
        try {
            val readBytes = channel.read(buffer)
            
            if (readBytes > 0) {
                iterator.remove()
                
                // [التخنيق الرياضي الدقيق] يتم استدعاء الـ downloadBucket العام المتواجد في نفس الحزمة
                LocalVpnService.downloadBucket.consume(readBytes.toLong())
                
                // هنا يتم إعداد البيانات المقروءة وتحويلها إلى حزمة IP/TCP مصطنعة لإرسالها للهاتف
                buffer.flip()
                
                // نقوم بإنشاء البايت بفر النهائي وضبط الموضع (Offset 28 بايت لترويسة الـ IP والـ TCP)
                val packetBuffer = ByteBuffer.allocate(readBytes + 28)
                packetBuffer.position(28)
                packetBuffer.put(buffer)
                packetBuffer.position(readBytes + 28)
                
                // إرسال البيانات المعبأة إلى طابور المعالجة وضخها في نفق الـ VPN فورًا
                outputQueue.put(packetBuffer)
                
            } else if (readBytes < 0) {
                // إشارة إغلاق الاتصال من الطرف الآخر (FIN)
                iterator.remove()
                Log.d("TcpSelectorEngine", "السيرفر قام بإنهاء الجلسة: ${session.sessionKey}")
                VpnConnectionSession.closeSession(session)
            }
        } catch (e: Exception) {
            iterator.remove()
            Log.e("TcpSelectorEngine", "حدث خطأ أثناء قراءة البيانات وخنقها: ${e.message}")
            VpnConnectionSession.closeSession(session)
        }
    }
}
