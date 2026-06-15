package com.qasim.speedlimiter.data.services

import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

class TokenBucket(
    val capacity: Long,        
    val refillRate: Long,      
    private val unitNano: Long = 1_000_000_000L 
) {
    private var tokens = AtomicLong(capacity) 
    private var lastRefillTimestamp = AtomicLong(System.nanoTime())

    fun tryAcquire(tokensToConsume: Long): Boolean {
        if (tokensToConsume <= 0) return true
        if (tokensToConsume > capacity) return false

        refill()

        while (true) {
            val currentTokens = tokens.get()
            if (currentTokens < tokensToConsume) {
                return false
            }
            if (tokens.compareAndSet(currentTokens, currentTokens - tokensToConsume)) {
                return true
            }
        }
    }

    fun consume(tokensToConsume: Long) {
        while (!tryAcquire(tokensToConsume)) {
            val missingTokens = tokensToConsume - tokens.get()
            if (missingTokens > 0) {
                val waitTimeMs = (missingTokens * 1000) / refillRate
                Thread.sleep(min(waitTimeMs, 100L).coerceAtLeast(1L))
            }
        }
    }

    private fun refill() {
        val now = System.nanoTime()
        val lastRefill = lastRefillTimestamp.get()
        val elapsedTime = now - lastRefill

        if (elapsedTime <= 0) return

        val tokensToAdd = (elapsedTime * refillRate) / unitNano
        if (tokensToAdd > 0) {
            if (lastRefillTimestamp.compareAndSet(lastRefill, now)) {
                while (true) {
                    val currentTokens = tokens.get()
                    val newTokens = min(capacity, currentTokens + tokensToAdd)
                    if (tokens.compareAndSet(currentTokens, newTokens)) {
                        break
                    }
                }
            }
        }
    }

    fun getAvailableTokens(): Long {
        refill()
        return tokens.get()
    }
}
