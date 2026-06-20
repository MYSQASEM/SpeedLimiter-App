package com.qasim.speedlimiter.data.services

class NetworkThrottler(
    private var maxBucketSize: Long, 
    private var tokensPerSecond: Long
) {
    private var availableTokens: Long = maxBucketSize
    private var lastRefillTime: Long = System.currentTimeMillis()
    var isPaused: Boolean = false

    @Synchronized
    fun limit(bytes: Long) {
        while (true) {
            if (isPaused) {
                try {
                    (this as Object).wait(60000L)
                } catch (e: Exception) {}
            } else {
                refill()
                if (availableTokens >= bytes) {
                    break
                }
                // حساب عدد الملي ثواني المطلوبة بدقة لامتلاء الباكت للحزمة الحالية
                var sleepTime = ((bytes - availableTokens) * 1000) / tokensPerSecond
                if (sleepTime < 1) {
                    sleepTime = 1
                }
                try {
                    (this as Object).wait(sleepTime)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
        availableTokens -= bytes
    }

    private fun refill() {
        val now = System.currentTimeMillis()
        val timePassed = now - lastRefillTime
        if (timePassed > 0) {
            val generatedTokens = (timePassed * tokensPerSecond) / 1000
            if (generatedTokens > 0) {
                val newTokens = availableTokens + generatedTokens
                availableTokens = if (newTokens > maxBucketSize) maxBucketSize else newTokens
                lastRefillTime = now
            }
        }
    }

    @Synchronized
    fun updateSpeed(maxSize: Long, perSecond: Long) {
        this.maxBucketSize = maxSize
        this.tokensPerSecond = perSecond
        if (availableTokens > maxSize) {
            availableTokens = maxSize
        }
        (this as Object).notifyAll()
    }
}

