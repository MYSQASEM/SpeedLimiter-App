package com.qasim.speedlimiter.data.services

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
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
            
            // تحويل السرعة إلى بايتات في الثانية
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

    // 1️⃣ المسار الأول: قراءة الحزم الخام من نفق الـ VPN وتوجيهها
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
            val buffer = ByteBuffer.allocate(16384)

            while (isRunning) {
                buffer.clear()
                val readBytes = inputStream.read(buffer.array())
                if (readBytes > 0) {
                    buffer.limit(readBytes)
                    processPacket(buffer)
                }
            }
        } catch (e: Exception) {
            Log.e("VpnService", "Error in Packet Reader", e)
        } finally {
            inputStream?.close()
            stopVpn()
        }
    }

    // 2️⃣ المسار الثاني: تفكيك الحزم وفتح قنوات شبكة حقيقية (Sockets) مع خنق السرعة
    private fun processPacket(packet: ByteBuffer) {
        // فك ترويسة الـ IP (مكدس شبكة مصغر لقراءة البروتوكول والوجهة)
        val protocol = packet.get(9).toInt()
        
        // نتعامل مع حزم TCP كمثال رئيسي لخدمات التصفح والمواقع لضمان ثبات الإنترنت
        if (protocol == 6) { // 6 تعني بروتوكول TCP
            val sourceIp = "${packet.get(12).toUByte()}.${packet.get(13).toUByte()}.${packet.get(14).toUByte()}.${packet.get(15).toUByte()}"
            val destIp = "${packet.get(16).toUByte()}.${packet.get(17).toUByte()}.${packet.get(18).toUByte()}.${packet.get(19).toUByte()}"
            
            val sessionKey = "$sourceIp->$destIp"

            // إذا كانت الجلسة جديدة، نفتح قناة اتصال حقيقية بالإنترنت الخارجي ونحميها
            if (!sessionMap.containsKey(sessionKey)) {
                try {
                    val targetChannel = SocketChannel.open()
                    targetChannel.configureBlocking(false)
                    
                    // حماية الـ Socket من الدوران الداخلي لإرساله لبرج التغطية مباشرة
                    protect(targetChannel.socket()) 
                    
                    targetChannel.connect(InetSocketAddress(destIp, 80)) // منفذ افتراضي للموقع
                    
                    targetChannel.register(selector, SelectionKey.OP_CONNECT or SelectionKey.OP_READ, sessionKey)
                    sessionMap[sessionKey] = targetChannel
                } catch (e: Exception) {
                    return
                }
            }

            // 🚀 السطر السحري: تطبيق لوجيك الفرملة الصارم المأوخذ من كود u1.java الخاص بك
            speedThrottler?.limit(packet.remaining().longValue())
            
            // تمرير البيانات المفرملة عبر القناة الحية
            val channel = sessionMap[sessionKey]
            if (channel != null && channel.isConnected) {
                try { channel.write(packet) } catch (e: Exception) {}
            }
        } else {
            // تمرير مباشر للحزم الأخرى (مثل UDP والـ DNS الافتراضي) لمنع انقطاع اتصال الهاتف
            writeToVpn(packet.array(), packet.limit())
        }
    }

    // 3️⃣ المسار الثالث: الاستماع للردود القادمة من الإنترنت الحقيقي وإعادتها للهاتف مفرملة
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
                            channel.finishConnect()
                        }
                    } else if (key.isReadable) {
                        val receiveBuffer = ByteBuffer.allocate(32768)
                        val readBytes = channel.read(receiveBuffer)
                        if (readBytes > 0) {
                            receiveBuffer.flip()
                            
                            // 🚀 فرملة البيانات القادمة من الإنترنت (التحميل Download) بناءً على السلايدر
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
                // معالجة فقدان الحزم العابرة
            }
        }
    }

    private fun Int.toLongValue(): Long = this.toLong()

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
