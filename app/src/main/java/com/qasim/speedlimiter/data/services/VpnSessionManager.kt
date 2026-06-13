package com.qasim.speedlimiter.data.services

import java.net.InetSocketAddress
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap

class VpnSessionManager(private val uploadBucket: TokenBucket, private val downloadBucket: TokenBucket) {

    private val sessions = ConcurrentHashMap<String, TcpSession>()

    class TcpSession(val remoteChannel: SocketChannel)

    fun handleTcpData(
        sourceIp: String, sourcePort: Int,
        destIp: String, destPort: Int,
        data: ByteArray
    ) {
        val sessionKey = "$sourceIp:$sourcePort->$destIp:$destPort"
        var session = sessions[sessionKey]
        
        if (session == null) {
            try {
                val channel = SocketChannel.open()
                channel.configureBlocking(false)
                channel.connect(InetSocketAddress(destIp, destPort))
                session = TcpSession(channel)
                sessions[sessionKey] = session
            } catch (_: Exception) {
                return
            }
        }

        uploadBucket.consume(data.size.toLong())
        
        try {
            if (session.remoteChannel.finishConnect()) {
                session.remoteChannel.write(java.nio.ByteBuffer.wrap(data))
            }
        } catch (_: Exception) {}
    }

    fun readFromInternet(onDataReceived: (String, Int, String, Int, ByteArray) -> Unit) {
        for ((key, session) in sessions) {
            try {
                val buffer = java.nio.ByteBuffer.allocate(16384)
                val read = session.remoteChannel.read(buffer)
                
                if (read > 0) {
                    downloadBucket.consume(read.toLong())
                    
                    val data = ByteArray(read)
                    buffer.flip()
                    buffer.get(data)
                    
                    val parts = key.split("->", ":")
                    if (parts.size >= 4) {
                        onDataReceived(parts[2], parts[3].toInt(), parts[0], parts[1].toInt(), data)
                    }
                } else if (read == -1) {
                    session.remoteChannel.close()
                    sessions.remove(key)
                }
            } catch (_: Exception) {
                sessions.remove(key)
            }
        }
    }
}