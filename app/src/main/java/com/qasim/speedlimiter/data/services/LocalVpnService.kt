package com.qasim.speedlimiter.data.services

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

class LocalVpnService : VpnService(), Runnable {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private var isRunning = false
    private var speedLimitKbps: Int = 1024

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "START") {
            val sharedPrefs = getSharedPreferences("SpeedLimiterPrefs", Context.MODE_PRIVATE)
            speedLimitKbps = sharedPrefs.getInt("speed_limit", 1024)
            
            if (!isRunning) {
                isRunning = true
                vpnThread = Thread(this, "SpeedVpnThread")
                vpnThread?.start()
            }
        } else if (action == "STOP") {
            stopVpn()
        }
        return START_STICKY
    }

    override fun run() {
        try {
            val builder = Builder()
            builder.setSession("SpeedLimiterPass")
                   .addAddress("192.168.2.2", 24)
                   .addDnsServer("8.8.8.8")
                   .addRoute("0.0.0.0", 0)

            // قصر التحكم على المتصفح وتطبيقات معينة لضمان التمرير وتجنب الـ Loopback
            val targetApps = listOf("com.android.chrome", "com.google.android.youtube")
            for (app in targetApps) {
                try { builder.addAllowedApplication(app) } catch (e: Exception) {}
            }

            vpnInterface = builder.establish() ?: return

            val inputStream = FileInputStream(vpnInterface!!.fileDescriptor)
            val outputStream = FileOutputStream(vpnInterface!!.fileDescriptor)
            val buffer = ByteArray(16384)

            var bytesSent = 0
            var lastCheck = System.currentTimeMillis()

            while (isRunning) {
                val readBytes = inputStream.read(buffer)
                if (readBytes > 0) {
                    bytesSent += readBytes
                    val maxBytesPerSecond = (speedLimitKbps * 1024) / 8
                    val now = System.currentTimeMillis()

                    if (now - lastCheck < 1000) {
                        if (bytesSent >= maxBytesPerSecond) {
                            val delay = 1000 - (now - lastCheck)
                            if (delay > 0) Thread.sleep(delay)
                            bytesSent = 0
                            lastCheck = System.currentTimeMillis()
                        }
                    } else {
                        bytesSent = 0
                        lastCheck = now
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
