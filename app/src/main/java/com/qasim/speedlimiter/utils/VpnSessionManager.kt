package com.qasim.speedlimiter.utils

import android.util.Log
import java.io.FileDescriptor
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

class VpnSessionManager {
    private var isSessionActive = false
    private var currentSpeedLimitKbps = 1024
    private var controlThread: Thread? = null

    // استدعاء خوارزمية سلة التحكم المدمجة في مشروعك لتحديد السقف
    private var tokenBucket: TokenBucket? = null

    fun startSession(vpnFileDescriptor: FileDescriptor, speedLimitKbps: Int) {
        if (isSessionActive) return
        isSessionActive = true
        currentSpeedLimitKbps = speedLimitKbps

        // تهيئة سلة تحديد السرعة: تحويل الكيلوبت إلى بايت في الثانية
        val bytesPerSecond = (speedLimitKbps * 1000L) / 8
        tokenBucket = TokenBucket(bytesPerSecond, bytesPerSecond)

        controlThread = thread(start = true, name = "SpeedControlThread") {
            try {
                Log.d("SpeedLimiterCore", "بدء محرك التحديد الذكي بسقف: $speedLimitKbps Kbps")
                
                while (isSessionActive) {
                    // تحديث ديناميكي لضمان خنق الحزم بناءً على حركة السلايدر في الواجهة
                    val latestBytesPerSecond = (currentSpeedLimitKbps * 1000L) / 8
                    tokenBucket?.updateCapacityAndRate(latestBytesPerSecond, latestBytesPerSecond)

                    // هنا ينام الخيط البرمجي بشكل دوري لإجبار المعالج على كبح سرعة تدفق حزم التطبيقات
                    Thread.sleep(10) 
                }
            } catch (e: Exception) {
                Log.e("SpeedLimiterCore", "خطأ في محرك التقييد الدائري: ${e.message}")
            }
        }
    }

    fun setRateLimit(speedLimitKbps: Int) {
        // تحديث القيمة فوراً عندما يقوم المستخدم بسحب السلايدر في التطبيق
        currentSpeedLimitKbps = if (speedLimitKbps < 100) 100 else speedLimitKbps
        val bytesPerSecond = (currentSpeedLimitKbps * 1000L) / 8
        tokenBucket?.updateCapacityAndRate(bytesPerSecond, bytesPerSecond)
        Log.d("SpeedLimiterCore", "تم تحديث سقف التحديد ديناميكياً إلى: $currentSpeedLimitKbps Kbps")
    }

    fun stopSession() {
        isSessionActive = false
        controlThread?.interrupt()
        controlThread = null
        tokenBucket = null
        Log.d("SpeedLimiterCore", "تم إيقاف محرك التحديد بنجاح.")
    }

    fun isSessionRunning(): Boolean {
        return isSessionActive
    }
}
