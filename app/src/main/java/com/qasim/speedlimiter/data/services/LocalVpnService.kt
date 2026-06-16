package com.qasim.speedlimiter.data.services

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

class LocalVpnService : VpnService(), Runnable {
    private var vpnThread: Thread? = null
    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "START") {
            startVpn()
        } else if (action == "STOP") {
            stopVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (vpnThread == null) {
            vpnThread = Thread(this, "LocalVpnThread")
            vpnThread?.start()
        }
    }

    private fun stopVpn() {
        vpnThread?.interrupt()
        vpnThread = null
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
        }
        vpnInterface = null
        stopSelf()
    }

    override fun run() {
        try {
            val builder = Builder()
            builder.setSession("SpeedLimiterVpn")
                .addAddress("10.0.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
            
            vpnInterface = builder.establish()
            val inputStream = FileInputStream(vpnInterface?.fileDescriptor)
            val outputStream = FileOutputStream(vpnInterface?.fileDescriptor)
            val buffer = ByteBuffer.allocate(16384)

            while (!Thread.interrupted()) {
                val readBytes = inputStream.read(buffer.array())
                if (readBytes > 0) {
                    outputStream.write(buffer.array(), 0, readBytes)
                    buffer.clear()
                }
                Thread.sleep(10)
            }
        } catch (e: Exception) {
        } finally {
            stopVpn()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
    }
}
