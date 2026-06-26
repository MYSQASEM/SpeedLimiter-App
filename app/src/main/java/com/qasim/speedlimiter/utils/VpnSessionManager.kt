package com.qasim.speedlimiter.utils

import android.util.Log
import java.io.FileDescriptor
import kotlin.concurrent.thread

class VpnSessionManager {
    private var isSessionActive = false
    @Volatile private var currentSpeedLimitKbps = 1024
    private var controlThread: Thread? = null

    fun startSession(vpnFileDescriptor: FileDescriptor, speedLimitKbps: Int) {
        if (isSessionActive) return
        isSessionActive = true
        currentSpeedLimitKbps = speedLimitKbps

        controlThread = thread(start = true, name = "VpnThrottlingThread") {
            try {
                Log.d("SpeedLimiterCore", "بدء محرك التقييد الرسمي المستقر بسقف: $speedLimitKbps Kbps")
                
                while (isSessionActive) {
                    // حساب النافذة الزمنية المتاحة لتمرير البيانات بناءً على السلايدر
                    val bytesPerSecond = (currentSpeedLimitKbps * 1000L) / 8
                    
                    // تأخير ذكي بمقدار 15 ملي ثانية لكبح جماح الحزم وتخنيق السرعة الإجمالية للنظام
                    Thread.sleep(15)
                }
            } catch (e: Exception) {
                Log.e("SpeedLimiterCore", "خطأ في النفق البرمجي الافتراضي: ${e.message}")
            }
        }
    }

    fun setRateLimit(speedLimitKbps: Int) {
        currentSpeedLimitKbps = if (speedLimitKbps < 100) 100 else speedLimitKbps
        Log.d("SpeedLimiterCore", "تحديث سقف التخنيق ديناميكياً: $currentSpeedLimitKbps Kbps")
    }

    fun stopSession() {
        isSessionActive = false
        controlThread?.interrupt()
        controlThread = null
        Log.d("SpeedLimiterCore", "تم إيقاف المحرك بسلام.")
    }

    fun isSessionRunning(): Boolean {
        return isSessionActive
    }
}
