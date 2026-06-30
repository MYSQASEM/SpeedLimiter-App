package com.qasim.speedlimiter.data.services

import android.util.Log
import com.qasim.speedlimiter.utils.VpnConnectionSession
import com.qasim.speedlimiter.utils.NetworkPacketUtils
import com.qasim.speedlimiter.data.services.LocalVpnService
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.BlockingQueue

/**
 * محرك معالجة البيانات واستقبالها من الإنترنت الحقيقي
 * يدير الاتصالات الصادرة والواردة ويطبق خنق السرعة الإجمالي عبر السلايدر
 */
class TcpSelectorEngine(
    private val selector: Selector,
    private val outputQueue: BlockingQueue<ByteBuffer> // الطابور الموجه لإعادة ضخ الحزم بالنفق
) : Runnable {

    companion object {
        private var instance: TcpSelectorEngine? = null

        // دالة ثابتة للوصول للمحرك من الـ VpnSessionManager وتوجيه الحزم الصادرة إليه
        fun processOutgoingPacket(packetBuffer: ByteBuffer) {
            instance?.handleOutgoingPacket(packetBuffer)
        }
    }

    init {
        instance = this
    }

    override fun run() {
        Log.d("TcpSelectorEngine", "بدأ خيط معالجة السوكتات والاستقبال الفعلي بالعمل المستقر...")
        
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
                    iterator.remove()
                    
                    if (key.isValid) {
                        if (key.isConnectable) {
                            handleConnect(key)
                        } else if (key.isReadable) {
                            handleRead(key)
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
     * 🚀 [الدالة المعجزة المفقودة]: استقبال الحزم الصادرة من تطبيقات الهاتف وربطها بالإنترنت
     */
    private fun handleOutgoingPacket(packet: ByteBuffer) {
        try {
            // 1. استخراج عناوين الـ IP والبورتات الحقيقية من داخل الحزمة القادمة من نظام الأندرويد
            packet.position(0)
            
            // قراءة الـ IPs (نحتاج تخطي أول 12 بايت في ترويسة IP للوصول للمصدر والهدف)
            val srcIpBuf = ByteArray(4)
            val destIpBuf = ByteArray(4)
            packet.position(12)
            packet.get(srcIpBuf)
            packet.get(destIpBuf)
            
            val sourceAddress = InetAddress.getByAddress(srcIpBuf)
            val destAddress = InetAddress.getByAddress(destIpBuf)

            // الوصول لترويسة الـ TCP (تبدأ بعد 20 بايت من الـ IP ترويسة)
            packet.position(20)
            val sourcePort = packet.short.toInt() and 0xFFFF
            val destPort = packet.short.toInt() and 0xFFFF
            
            val sessionKey = "${sourceAddress.hostAddress}:$sourcePort -> ${destAddress.hostAddress}:$destPort"

            // 2. التحقق من وجود جلسة سابقة أو إنشاء واحدة جديدة فوراً
            var session = VpnConnectionSession.getSession(sessionKey)
            if (session == null) {
                session = VpnConnectionSession().apply {
                    this.sessionKey = sessionKey
                    this.localAddressIp = ByteBuffer.wrap(srcIpBuf).int
                    this.remoteAddressIp = ByteBuffer.wrap(destIpBuf).int
                    this.localPort = sourcePort
                    this.remotePort = destPort
                }
                
                // 3. فتح قناة اتصال حقيقية (SocketChannel) خارج الـ VPN متجهة للسيرفر الحقيقي
                val socketChannel = SocketChannel.open()
                socketChannel.configureBlocking(false)
                
                // حماية السوكت لمنع دخوله في حلقة مفرغة مع الـ VPN (توجيهه للإنترنت الحقيقي عبر الواي فاي أو البيانات)
                // ملحوظة: الـ LocalVpnService يجب أن يحتوي على دالة الـ protect القياسية
                // (context as VpnService).protect(socketChannel.socket())

                socketChannel.connect(InetSocketAddress(destAddress, destPort))
                
                session.channel = socketChannel
                // تسجيل السوكت داخل الـ Selector لمراقبة لحظة إتمام الاتصال
                session.selectionKey = socketChannel.register(selector, SelectionKey.OP_CONNECT, session)
                
                VpnConnectionSession.addSession(sessionKey, session)
                Log.d("TcpSelectorEngine", "🚀 تم إنشاء اتصال إنترنت حقيقي جديد لتطبيق الهاتف: $sessionKey")
            }

            // 4. إذا كان هناك بيانات (Payload) داخل الحزمة الصادرة، نقوم بكتابتها فوراً للسيرفر الحقيقي
            val ipHeaderLength = 20
            val tcpHeaderLength = 20 // تبسيطاً للسرعة
            val totalHeaderLength = ipHeaderLength + tcpHeaderLength
            
            packet.position(0)
            val totalLength = packet.short.toInt() and 0xFFFF
            val payloadLength = totalLength - totalHeaderLength

            if (payloadLength > 0 && session.connectionState == 2) {
                packet.position(totalHeaderLength)
                val payload = ByteBuffer.allocate(payloadLength)
                val backupLimit = packet.limit()
                packet.limit(totalHeaderLength + payloadLength)
                payload.put(packet)
                packet.limit(backupLimit)
                payload.flip()
                
                while (payload.hasRemaining()) {
                    session.channel?.write(payload)
                }
            }
        } catch (e: Exception) {
            Log.e("TcpSelectorEngine", "فشل توجيه الحزمة الصادرة للإنترنت: ${e.message}")
        }
    }

    private fun handleConnect(key: SelectionKey) {
        val session = key.attachment() as? VpnConnectionSession ?: return
        val channel = key.channel() as SocketChannel
        
        try {
            if (channel.finishConnect()) {
                session.connectionState = 2 // متصل الآن بنجاح
                key.interestOps(SelectionKey.OP_READ)
                Log.d("TcpSelectorEngine", "تم الاتصال بنجاح بالسيرفر الخارجي للجلسة: ${session.sessionKey}")
            }
        } catch (e: IOException) {
            Log.e("TcpSelectorEngine", "فشل إتمام الاتصال بالسيرفر: ${e.message}")
            VpnConnectionSession.closeSession(session)
        }
    }

    private fun handleRead(key: SelectionKey) {
        val session = key.attachment() as? VpnConnectionSession ?: return
        val channel = key.channel() as SocketChannel
        val buffer = ByteBuffer.allocate(16384)
        
        try {
            val readBytes = channel.read(buffer)
            
            if (readBytes > 0) {
                // 🚀 [التحكم الصارم بالسلايدر] استهلاك بايتات التحميل الحقيقية القادمة من الإنترنت فوراً من الـ TokenBucket
                LocalVpnService.downloadBucket.consume(readBytes.toLong())
                
                buffer.flip()
                val packetBuffer = ByteBuffer.allocate(readBytes + 40)
                
                // بناء الترويسات الرياضية السليمة ليفهمها الأندرويد دون إسقاط الحزمة
                NetworkPacketUtils.buildTcpPacket(packetBuffer, session, buffer, readBytes)
                
                session.sendNextSequenceNumber += readBytes
                outputQueue.put(packetBuffer)
                
            } else if (readBytes < 0) {
                Log.d("TcpSelectorEngine", "السيرفر قام بإنهاء الجلسة: ${session.sessionKey}")
                VpnConnectionSession.closeSession(session)
            }
        } catch (e: Exception) {
            Log.e("TcpSelectorEngine", "حدث خطأ أثناء قراءة البيانات وخنقها: ${e.message}")
            VpnConnectionSession.closeSession(session)
        }
    }
}
