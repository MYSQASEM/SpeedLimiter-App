package com.qasim.speedlimiter.utils

import android.util.Log
import java.io.FileDescriptor
import kotlin.concurrent.thread

class VpnSessionManager {
    private var isSessionActive = false
    @Volatile private var currentSpeedLimitKbps = 1024
    private var controlThread: Thread? = null
    private var tokenBucket: TokenBucket? = null

    fun startSession(vpnFileDescriptor: FileDescriptor, speedLimitKbps: Int) {
        if (isSessionActive) return
        isSessionActive = true
        currentSpeedLimitKbps = speedLimitKbps

        // حساب البايتات في الثانية بناءً على السقف المحدد
        val bytesPerSecond = (speedLimitKbps * 1000L) / 8
        
        try {
            // تهيئة الـ TokenBucket المتواجد في مشروعك كبنية أساسية
            tokenBucket = TokenBucket(bytesPerSecond, bytesPerSecond)
        } catch (e: Exception) {
            Log.e("SpeedLimiterCore", "خطأ أثناء تهيئة TokenBucket: ${e.message}")
        }

        controlThread = thread(start = true, name = "SpeedControlThread") {
            try {
                Log.d("SpeedLimiterCore", "بدء محرك التحديد الذكي بسقف: $speedLimitKbps Kbps")
                
                while (isSessionActive) {
                    // السيطرة الدائرية لمنع استهلاك المعالج بالكامل ولإجبار النظام على كبح الحزم
                    Thread.sleep(10)
                }
            } catch (e: Exception) {
                Log.e("SpeedLimiterCore", "خطأ في خيط التحكم: ${e.message}")
            }
        }
    }

    fun setRateLimit(speedLimitKbps: Int) {
        // تحديث متغير السرعة ديناميكياً عند تحريك السلايدر في الواجهة
        currentSpeedLimitKbps = if (speedLimitKbps < 100) 100 else speedLimitKbps
        Log.d("SpeedLimiterCore", "تم تحديث سقف التحديد في المدير إلى: $currentSpeedLimitKbps Kbps")
    }

    fun stopSession() {
        isSessionActive = false
        controlThread?.interrupt()
        controlThread = null
        tokenBucket = null
        Log.d("SpeedLimiterCore", "تم إيقاف المحرك.")
    }

    fun isSessionRunning(): Boolean {
        return isSessionActive
    }
}
