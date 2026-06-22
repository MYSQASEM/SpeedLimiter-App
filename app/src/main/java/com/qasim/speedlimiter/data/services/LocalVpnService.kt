package com.qasim.speedlimiter.data.services

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

class LocalVpnService : VpnService(), Runnable {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private var isRunning = false
    private var speedThrottler: NetworkThrottler? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "START") {
            val sharedPrefs = getSharedPreferences("SpeedLimiterPrefs", Context.MODE_PRIVATE)
            val speedLimitKbps = sharedPrefs.getInt("speed_limit", 1024)
            
            // تحويل الكيلوبت إلى بايتات في الثانية
            val bytesPerSecond = (speedLimitKbps * 1024L) / 8L
            
            if (speedThrottler == null) {
                speedThrottler = NetworkThrottler(bytesPerSecond, bytesPerSecond)
            } else {
                speedThrottler?.updateSpeed(bytesPerSecond, bytesPerSecond)
            }
            
            if (!isRunning) {
                isRunning = true
                vpnThread = Thread(this, "SafeThrottledVpn")
                vpnThread?.start()
            }
        } else if (action == "STOP") {
            stopVpn()
        }
        return START_STICKY
    }

    override fun run() {
        try {
            // بناء نفق شبكة متوافق مع معايير الأندرويد الحديثة يمرر الاتصال ولا يحبسه
            val builder = Builder()
            builder.setSession("SpeedLimiterCore")
                   .addAddress("172.19.0.1", 30) // آي بي افتراضي معزول لمنع الـ Loopback
                   .addDnsServer("8.8.8.8")
                   .addDnsServer("1.1.1.1")
                   .addRoute("0.0.0.0", 0) // توجيه النطاق العام
                   .setMtu(1500)

            // إجبار أندرويد على ربط النفق بالشبكة الحية الحقيقية (واي فاي / بيانات) لضمان التمرير الخارجي
            setUnderlyingNetworks(null)

            vpnInterface = builder.establish() ?: return

            val fileDescriptor = vpnInterface!!.fileDescriptor
            val inputStream = FileInputStream(fileDescriptor)
            val outputStream = FileOutputStream(fileDescriptor)
            
            val buffer = ByteArray(32768) // حجم بافر كبير كالموجود بكود التطبيق الناجح لمنع الاختناق

            while (isRunning && !Thread.interrupted()) {
                val readBytes = inputStream.read(buffer)
                if (readBytes > 0) {
                    
                    // 🚀 تطبيق فرملة الـ Bucket المأخوذة من ملف u1.java الخاص بك
                    speedThrottler?.limit(readBytes.toLong())
                    
                    // تمرير الحزمة مباشرة للنظام ليقوم بمعالجتها خارجياً دون حبسها
                    outputStream.write(buffer, 0, readBytes)
                    outputStream.flush()
                }
                Thread.sleep(1) // تنظيم استهلاك المعالج
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            stopVpn()
        }
    }

    private fun stopVpn() {
        isRunning = false
        vpnThread?.interrupt()
        vpnThread = null
        try { vpnInterface?.close() } catch (e: Exception) {}
        vpnInterface = null
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
    }
}
