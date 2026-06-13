package com.qasim.speedlimiter.data.services

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class LocalVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false

    companion object {
        private val blockedAppsMap = ConcurrentHashMap<String, Boolean>()

        fun setAppBlockState(packageName: String, isBlocked: Boolean) {
            if (isBlocked) {
                blockedAppsMap[packageName] = true
            } else {
                blockedAppsMap.remove(packageName)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        setupVpnInterface()
        startProcessing()
        return START_STICKY
    }

    private fun setupVpnInterface() {
        val builder = Builder()
            .setSession("NetGuardSpeedLimiter")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .setMtu(1500)

        blockedAppsMap.keys().forEach { packageName ->
            try {
                builder.addAllowedApplication(packageName)
            } catch (_: Exception) {}
        }

        vpnInterface = builder.establish()
    }

    private fun startProcessing() {
        thread(start = true, name = "VpnCoreEngine") {
            val descriptor = vpnInterface?.fileDescriptor ?: return@thread
            val inputStream = FileInputStream(descriptor)
            val outputStream = FileOutputStream(descriptor)
            val packetBuffer = ByteBuffer.allocate(16384)

            while (isRunning) {
                try {
                    val readBytes = inputStream.read(packetBuffer.array())
                    if (readBytes > 0) {
                        packetBuffer.rewind()
                        packetBuffer.limit(readBytes)
                        
                        // تمرير الحزم بشكل فوري مباشر للإنترنت لمنع انقطاع الشبكة للهاتف
                        outputStream.write(packetBuffer.array(), 0, readBytes)
                    }
                    Thread.sleep(2)
                } catch (_: Exception) {}
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        try { vpnInterface?.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}