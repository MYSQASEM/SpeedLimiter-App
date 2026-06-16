package com.qasim.speedlimiter.utils

class VpnSessionManager {
    private var isSessionActive = false

    fun startSession() {
        isSessionActive = true
    }

    fun stopSession() {
        isSessionActive = false
    }

    fun isSessionRunning(): Boolean {
        return isSessionActive
    }
}
