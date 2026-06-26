package com.qasim.speedlimiter.utils

import android.util.Log
import java.io.FileDescriptor

class VpnSessionManager {
    private var isSessionActive = false
    @Volatile private var currentSpeedLimitKbps = 1024

    fun startSession(vpnFileDescriptor: FileDescriptor, speedLimitKbps: Int) {
        isSessionActive = true
        currentSpeedLimitKbps = speedLimitKbps
        Log.d("SpeedLimiterCore", "تم تفعيل محرك نظام أندرويد للتحديد بسقف: $speedLimitKbps Kbps")
    }

    fun setRateLimit(speedLimitKbps: Int) {
        currentSpeedLimitKbps = if (speedLimitKbps < 100) 100 else speedLimitKbps
        Log.d("SpeedLimiterCore", "تحديث سقف السرعة في المدير: $currentSpeedLimitKbps Kbps")
    }

    fun stopSession() {
        isSessionActive = false
        Log.d("SpeedLimiterCore", "تم إيقاف محرك التحديد.")
    }

    fun isSessionRunning(): Boolean {
        return isSessionActive
    }
}
