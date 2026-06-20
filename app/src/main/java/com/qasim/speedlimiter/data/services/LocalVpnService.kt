package com.qasim.speedlimiter.data.services

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class LocalVpnService : VpnService(), Runnable {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private var selectorThread: Thread? = null
    private var isRunning = false
    
    private var speedThrottler: NetworkThrottler? = null
    private var selector: Selector? = null
    private val outputQueue = Executors.newSingleThreadExecutor()
    private val sessionMap = ConcurrentHashMap<String, SocketChannel>()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "START") {
            val sharedPrefs = getSharedPreferences("SpeedLimiterPrefs", Context.MODE_PRIVATE)
            val speedLimitKbps = sharedPrefs.getInt("speed_limit", 1024)
            
            val bytesPerSecond = (speedLimitKbps * 1024L) / 8L
            
            if (speedThrottler == null) {
                speedThrottler = NetworkThrottler(bytesPerSecond, bytesPerSecond)
            } else {
                speedThrottler?.updateSpeed(bytesPerSecond, bytesPerSecond)
            }
            
            if (!isRunning) {
                isRunning = true
                selector = Selector.open()
                
                vpnThread = Thread(this, "VpnPacketReader")
                vpnThread?.start()
                
                selectorThread = Thread({ runSelector() }, "VpnSelectorThread")
                selectorThread?.start()
            }
        } else if (action == "STOP") {
            stopVpn()
        }
        return START_STICKY
    }

    override fun run() {
        var inputStream: FileInputStream? = null
        try {
            val builder = Builder()
            builder.setSession("SpeedLimiterPro")
                   .addAddress("10.0.0.2", 32)
                   .addRoute("0.0.0.0", 0)
                   .addDnsServer("8.8.8.8")
                   .setMtu(1500)

            vpnInterface = builder.establish() ?: return
            inputStream = FileInputStream(vpnInterface!!.fileDescriptor)
            val buffer = ByteArray(16384)

            while (isRunning) {
                val readBytes = inputStream.read(buffer)
                if (readBytes > 0) {
                    val packet = ByteBuffer.wrap(buffer, 0, readBytes)
                    processPacket(packet)
                }
            }
        } catch (e: Exception) {
            Log.e("VpnService", "Error in Packet Reader", e)
        } finally {
            try { inputStream?.close() } catch (e: Exception) {}
            stopVpn()
        }
    }

    private fun processPacket(packet: ByteBuffer) {
        if (packet.remaining() < 20) return
        
        val protocol = packet.get(9).toInt() and 0xFF
        
        if (protocol == 6) { // TCP Protocol
            val sourceIp = "${packet.get(12).toInt() and 0xFF}.${packet.get(13).toInt() and 0xFF}.${packet.get(14).toInt() and 0xFF}.${packet.get(15).toInt() and 0xFF}"
            val destIp = "${packet.get(16).toInt() and 0xFF}.${packet.get(17).toInt() and 0xFF}.${packet.get(18).toInt() and 0xFF}.${packet.get(19).toInt() and 0xFF}"
            
            val sessionKey = "$sourceIp->$destIp"

            if (!sessionMap.containsKey(sessionKey)) {
                try {
                    val targetChannel = SocketChannel.open()
                    targetChannel.configureBlocking(false)
                    protect(targetChannel.socket()) 
                    targetChannel.connect(InetSocketAddress(destIp, 80)) 
                    
                    targetChannel.register(selector, SelectionKey.OP_CONNECT or SelectionKey.OP_READ, sessionKey)
                    sessionMap[sessionKey] = targetChannel
                } catch (e: Exception) {
                    return
                }
            }

            // 🚀 ضمان تحويل حجم الحزمة إلى Long بشكل صريح ومباشر
            val packetSize = packet.remaining().toLong()
            speedThrottler?.limit(packetSize)
            
            val channel = sessionMap[sessionKey]
            if (channel != null && channel.isConnected) {
                try { channel.write(packet) } catch (e: Exception) {}
            }
        } else {
            // تمرير الحزم الأخرى مع التأكد من تحويل الأحجام لتفادي أي خطأ بنيوي
            val dataSize = packet.remaining()
            val dataCopy = ByteArray(dataSize)
            packet.get(dataCopy)
            writeToVpn(dataCopy, dataSize)
        }
    }

    private fun runSelector() {
        try {
            while (isRunning && selector != null) {
                if (selector!!.select(10) == 0) continue
                val keys = selector!!.selectedKeys().iterator()
                while (keys.hasNext()) {
                    val key = keys.next()
                    keys.remove()
                    if (!key.isValid) continue

                    val sessionKey = key.attachment() as String
                    val channel = key.channel() as SocketChannel

                    if (key.isConnectable) {
                        if (channel.isConnectionPending) {
                            try { channel.finishConnect() } catch (e: Exception) { key.cancel() }
                        }
                    } else if (key.isReadable) {
                        val receiveBuffer = ByteBuffer.allocate(32768)
                        val readBytes = channel.read(receiveBuffer)
                        if (readBytes > 0) {
                            receiveBuffer.flip()
                            
                            // 🚀 فرملة الـ Download بشكل صريح بتحويل القيمة إلى Long
                            speedThrottler?.limit(readBytes.toLong())
                            
                            val responseData = ByteArray(readBytes)
                            receiveBuffer.get(responseData)
                            writeToVpn(responseData, readBytes)
                        } else if (readBytes == -1) {
                            channel.close()
                            sessionMap.remove(sessionKey)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("VpnService", "Error in Selector Loop", e)
        }
    }

    private fun writeToVpn(data: ByteArray, length: Int) {
        outputQueue.submit {
            try {
                vpnInterface?.fileDescriptor?.let { fd ->
                    val outputStream = FileOutputStream(fd)
                    outputStream.write(data, 0, length)
                    outputStream.flush()
                }
            } catch (e: Exception) {
                // تخطي حزم الخرج العابرة
            }
        }
    }

    private fun stopVpn() {
        isRunning = false
        vpnThread?.interrupt()
        selectorThread?.interrupt()
        try { selector?.close() } catch (e: Exception) {}
        try { vpnInterface?.close() } catch (e: Exception) {}
        
        for (channel in sessionMap.values) {
            try { channel.close() } catch (e: Exception) {}
        }
        sessionMap.clear()
        vpnInterface = null
        selector = null
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
        outputQueue.shutdownNow()
    }
}
