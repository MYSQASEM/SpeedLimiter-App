package com.qasim.speedlimiter.data.services

import android.net.VpnService
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
    private val outputQueue: BlockingQueue<ByteBuffer>, // الطابور الموجه لإعادة ضخ الحزم بالنفق
    private val vpnService: VpnService // 🚀 تمرير مرجع الخدمة لتفعيل الـ protect
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
     * استقبال الحزم الصادرة من تطبيقات الهاتف وربطها بالإنترنت الخارجي بأمان
     */
    private fun handleOutgoingPacket(packet: ByteBuffer) {
        try {
            packet.position(0)
            
            val srcIpBuf = ByteArray(4)
            val destIpBuf = ByteArray(4)
            packet.position(12)
            packet.get(srcIpBuf)
            packet.get(destIpBuf)
            
            val sourceAddress = InetAddress.getByAddress(srcIpBuf)
            val destAddress = InetAddress.getByAddress(destIpBuf)

            packet.position(20)
            val sourcePort = packet.short.toInt() and 0xFFFF
            val destPort = packet.short.toInt() and 0xFFFF
            
            val sessionKey = "${sourceAddress.hostAddress}:$sourcePort -> ${destAddress.hostAddress}:$destPort"

            var session = VpnConnectionSession.getSession(sessionKey)
            if (session == null) {
                session = VpnConnectionSession().apply {
                    this.sessionKey = sessionKey
                    this.localAddressIp = ByteBuffer.wrap(srcIpBuf).int
                    this.remoteAddressIp = ByteBuffer.wrap(destIpBuf).int
                    this.localPort = sourcePort
                    this.remotePort = destPort
                }
                
                val socketChannel = SocketChannel.open()
                socketChannel.configureBlocking(false)
                
                // 🚀 [الإصلاح التاريخي الأخير]: حماية السوكت لمنعه من الدخول في حلقة مفرغة وتوجيهه للإنترنت الحقيقي فوراً
                vpnService.protect(socketChannel.socket())

                socketChannel.connect(InetSocketAddress(destAddress, destPort))
                
                session.channel = socketChannel
                session.selectionKey = socketChannel.register(selector, SelectionKey.OP_CONNECT, session)
                
                VpnConnectionSession.addSession(sessionKey, session)
                Log.d("TcpSelectorEngine", "🚀 تم حماية السوكت وربطه بالإنترنت الخارجي بنجاح: $sessionKey")
            }

            val ipHeaderLength = 20
            val tcpHeaderLength = 20
            val totalHeaderLength = ipHeaderLength + tcpHeaderLength
            
            packet.position(0)
            val totalLength = packet.short.toInt() and 0xFFFF
            val payloadLength = totalLength - totalHeaderLength

            if (payloadLength > 0) {
                packet.position(totalHeaderLength)
                val payload = ByteBuffer.allocate(payloadLength)
                val backupLimit = packet.limit()
                packet.limit(totalHeaderLength + payloadLength)
                payload.put(packet)
                packet.limit(backupLimit)
                payload.flip()
                
                if (session.connectionState == 2) {
                    while (payload.hasRemaining()) {
                        session.channel?.write(payload)
                    }
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
                session.connectionState = 2 
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
                
                NetworkPacketUtils.buildTcpPacket(packetBuffer, session, buffer, readBytes)
                
                session.sendNextSequenceNumber += readBytes
                
                // 🚀 إرسال البيانات المحقونة فوراً لطابور الإخراج ليتم قراءتها وضخها للهاتف
                packetBuffer.flip()
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
