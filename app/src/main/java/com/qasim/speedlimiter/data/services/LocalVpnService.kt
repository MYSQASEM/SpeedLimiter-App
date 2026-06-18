package com.qasim.speedlimiter.data.services

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream

class LocalVpnService : VpnService(), Runnable {
    private var vpnThread: Thread? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private var speedLimitKbps: Int = 1024 
    private var isRunning = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "START") {
            val sharedPrefs = getSharedPreferences("SpeedLimiterPrefs", Context.MODE_PRIVATE)
            speedLimitKbps = sharedPrefs.getInt("speed_limit", 1024)
            
            isRunning = true
            vpnThread = Thread(this, "PureVpnThread")
            vpnThread?.start()
        } else if (action == "STOP") {
            stopVpn()
        }
        return START_STICKY
    }

    override fun run() {
        try {
            val builder = Builder()
            builder.setSession("SpeedLimiterPro")
                   .addAddress("10.0.0.2", 32)
                   .addDnsServer("8.8.8.8")
                   .setMtu(1500)

            // لضمان عدم قطع الإنترنت نهائياً، نقوم بالتحكم في المتصفحات والتطبيقات الأساسية
            val appsToLimit = listOf(
                "com.android.chrome",
                "com.google.android.youtube",
                "com.instagram.android",
                "com.zhiliaoapp.musically"
            )

            for (app in appsToLimit) {
                try { builder.addAllowedApplication(app) } catch (e: Exception) {}
            }

            // توجيه المسار ليعمل كبوابة فحص وتمرير آمنة
            builder.addRoute("0.0.0.0", 0)

            vpnInterface = builder.establish() ?: return

            val inputStream = FileInputStream(vpnInterface!!.fileDescriptor)
            val outputStream = FileOutputStream(vpnInterface!!.fileDescriptor)
            val buffer = ByteArray(16384)

            var bytesProcessed = 0
            var lastCheckTime = System.currentTimeMillis()

            while (isRunning && !Thread.interrupted()) {
                val readBytes = inputStream.read(buffer)
                if (readBytes > 0) {
                    bytesProcessed += readBytes
                    
                    val maxBytesPerSecond = (speedLimitKbps * 1024) / 8
                    val now = System.currentTimeMillis()
                    val timePassed = now - lastCheckTime

                    if (timePassed < 1000) {
                        if (bytesProcessed >= maxBytesPerSecond) {
                            val sleepTime = 1000 - timePassed
                            if (sleepTime > 0) {
                                Thread.sleep(sleepTime) // خنق مباشر الحزم
                            }
                            bytesProcessed = 0
                            lastCheckTime = System.currentTimeMillis()
                        }
                    } else {
                        bytesProcessed = 0
                        lastCheckTime = now
                    }

                    outputStream.write(buffer, 0, readBytes)
                }
                Thread.sleep(1)
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
