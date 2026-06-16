package com.qasim.speedlimiter.utils

class TokenBucket(private val maxCapacity: Long, private val refillRatePerMs: Long) {
    private var tokens: Long = maxCapacity
    private var lastRefillTimestamp: Long = System.currentTimeMillis()

    @Synchronized
    fun consume(amount: Long): Boolean {
        refill()
        return if (tokens >= amount) {
            tokens -= amount
            true
        } else {
            false
        }
    }

    private fun refill() {
        val now = System.currentTimeMillis()
        val elapsedTime = now - lastRefillTimestamp
        if (elapsedTime > 0) {
            val tokensToAdd = elapsedTime * refillRatePerMs
            tokens = minOf(maxCapacity, tokens + tokensToAdd)
            lastRefillTimestamp = now
        }
    }
}
