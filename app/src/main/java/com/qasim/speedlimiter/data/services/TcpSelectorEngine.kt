package com.qasim.speedlimiter.data.services

import android.net.VpnService
import android.util.Log
import com.qasim.speedlimiter.utils.VpnConnectionSession
import com.qasim.speedlimiter.utils.NetworkPacketUtils
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.BlockingQueue

class TcpSelectorEngine(
    private val selector: Selector,
    private val outputQueue: BlockingQueue<ByteBuffer>,
    private val vpnService: VpnService // تمرير الخدمة مباشرة لحماية السوكت
) : Runnable {

    companion object {
        private var instance: TcpSelectorEngine? = null

        fun processOutgoingPacket(packetBuffer: ByteBuffer) {
            instance?.handleOutgoingPacket(packetBuffer)
        }
    }

    init {
        instance = this
    }

    override fun run() {
        Log.d("TcpSelectorEngine", "بدأ خيط معالجة السوكتات والاستقبال الفعلي...")
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
                Log.e("TcpSelectorEngine", "خطأ في حلقة الـ Selector: ${e.message}")
            }
        }
    }

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
                    this.localAddressIp = ByteBuffer.wrap(srcIpBuf).getInt()
                    this.remoteAddressIp = ByteBuffer.wrap(destIpBuf).getInt()
                    this.localPort = sourcePort
                    this.remotePort = destPort
                }
                
                val socketChannel = SocketChannel.open()
                socketChannel.configureBlocking(false)
                
                // حماية السوكت مباشرة عبر مرجع الخدمة الممرر
                vpnService.protect(socketChannel.socket())

                socketChannel.connect(InetSocketAddress(destAddress, destPort))
                
                session.channel = socketChannel
                session.selectionKey = socketChannel.register(selector, SelectionKey.OP_CONNECT, session)
                
                VpnConnectionSession.addSession(sessionKey, session)
            }

            val ipHeaderLength = 20
            val tcpHeaderLength = 20
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
            Log.e("TcpSelectorEngine", "فشل توجيه الحزمة: ${e.message}")
        }
    }

    private fun handleConnect(key: SelectionKey) {
        val session = key.attachment() as? VpnConnectionSession ?: return
        val channel = key.channel() as SocketChannel
        try {
            if (channel.finishConnect()) {
                session.connectionState = 2 
                key.interestOps(SelectionKey.OP_READ)
            }
        } catch (e: IOException) {
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
                LocalVpnService.downloadBucket.consume(readBytes.toLong())
                buffer.flip()
                val packetBuffer = ByteBuffer.allocate(readBytes + 40)
                NetworkPacketUtils.buildTcpPacket(packetBuffer, session, buffer, readBytes)
                session.sendNextSequenceNumber += readBytes
                packetBuffer.flip()
                outputQueue.put(packetBuffer)
            } else if (readBytes < 0) {
                VpnConnectionSession.closeSession(session)
            }
        } catch (e: Exception) {
            VpnConnectionSession.closeSession(session)
        }
    }
}
