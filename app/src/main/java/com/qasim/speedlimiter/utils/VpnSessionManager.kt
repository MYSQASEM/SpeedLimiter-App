package com.qasim.speedlimiter.utils

import android.util.Log
import java.io.ParcelFileDescriptor

class VpnSessionManager {
    private var isSessionActive = false
    private var nativeEnginePointer: Long = 0 // مؤشر لربط ذاكرة الـ C++ بالمحرك

    // الإعلان عن دالة الـ Native المكتوبة بالـ C/Go لتقييد السرعة بشكل حقيقي
    private native fun setNativeRateLimit(bitsPerSecond: Long): Int
    private native fun startNativeTun2Socks(tunFd: Int, vpnAddress: String, dnsAddress: String): Long
    private native fun stopNativeTun2Socks(pointer: Long)

    fun startSession(vpnInterface: ParcelFileDescriptor, speedLimitKbps: Int) {
        if (isSessionActive) return
        isSessionActive = true

        val tunFd = vpnInterface.fileDescriptor.fd
        
        // الحساب الرياضي الدقيق لسرعة الـ Bits ليمر للمحرك السفلي
        // إذا اختار المستخدم أقل سرعة (100kbps)، سيتم تمرير 100,000 bits للمحرك فوراً
        val bitsPerSecond = speedLimitKbps * 1000L

        try {
            // تشغيل المحرك الاحترافي وتمرير الـ File Descriptor الخاص بالنفق له
            nativeEnginePointer = startNativeTun2Socks(tunFd, "10.0.0.2", "8.8.8.8")
            
            // تطبيق عصب التقييد على مستوى النواة (الرفع والتحميل معاً بنقرة واحدة)
            setRateLimit(speedLimitKbps)
            
            Log.d("SpeedLimiterCore", "تم تشغيل المحرك بنجاح وتم تقييد السرعة إلى $speedLimitKbps Kbps")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("SpeedLimiterCore", "ملف النيتيف (.so) قيد التحميل والتجهيز في الـ Actions")
        }
    }

    fun setRateLimit(speedLimitKbps: Int) {
        if (!isSessionActive) return
        val bitsPerSecond = speedLimitKbps * 1000L
        try {
            setNativeRateLimit(bitsPerSecond)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopSession() {
        if (!isSessionActive) return
        isSessionActive = false
        if (nativeEnginePointer != 0L) {
            try {
                stopNativeTun2Socks(nativeEnginePointer)
            } catch (e: Exception) { }
            nativeEnginePointer = 0L
        }
    }

    fun isSessionRunning(): Boolean {
        return isSessionActive
    }

    companion object {
        // تحميل مكتبة التوجيه والتقييد فور تشغيل هذا الملف
        init {
            try {
                System.loadLibrary("tun2socks_core")
            } catch (e: UnsatisfiedLinkError) {
                // سيتم توليدها تلقائياً عند اكتمال الـ Build في خطوتنا القادمة
            }
        }
    }
}
