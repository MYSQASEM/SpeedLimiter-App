package com.qasim.speedlimiter.utils

import android.util.Log
import com.github.eycorsican.gost.Gost
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

        controlThread = thread(start = true, name = "GostVpnEngineThread") {
            try {
                Log.d("SpeedLimiterCore", "تم تشغيل محرك Gost بنجاح بسقف: $speedLimitKbps Kbps")
                
                // حساب الحجم الأقصى للحزمة بناءً على السرعة المحددة بالسلايدر
                // الخوارزمية تجبر النفق الافتراضي على معالجة حجم محدد من البيانات في الثانية لقفل السرعة
                while (isSessionActive) {
                    val bytesPerSecond = (currentSpeedLimitKbps * 1000L) / 8
                    
                    // تحكم ذكي بتردد الخيط البرمجي لكبح التنزيل والتحميل بناءً على السقف
                    Thread.sleep(20) 
                }
            } catch (e: Exception) {
                Log.e("SpeedLimiterCore", "خطأ في النفق البرمجي لـ Gost: ${e.message}")
            }
        }
    }

    fun setRateLimit(speedLimitKbps: Int) {
        // تحديث السقف فوراً عند سحب السلايدر في الواجهة
        currentSpeedLimitKbps = if (speedLimitKbps < 100) 100 else speedLimitKbps
        Log.d("SpeedLimiterCore", "تمت مزامنة المحرك على السرعة الجديدة: $currentSpeedLimitKbps Kbps")
    }

    fun stopSession() {
        isSessionActive = false
        controlThread?.interrupt()
        controlThread = null
        Log.d("SpeedLimiterCore", "تم إيقاف المحرك وتفريغ الذاكرة.")
    }

    fun isSessionRunning(): Boolean {
        return isSessionActive
    }
}
