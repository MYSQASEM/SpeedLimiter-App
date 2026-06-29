package com.qasim.speedlimiter.utils

import android.util.Log
import kotlin.math.min

class TokenBucket(initialCapacity: Long, initialRatePerSecond: Long) {
    
    @Volatile
    private var capacity: Double = initialCapacity.toDouble()
    
    @Volatile
    private var refillRatePerSecond: Double = initialRatePerSecond.toDouble()
    
    private var tokens: Double = initialCapacity.toDouble()
    private var lastRefillTimestamp: Long = System.currentTimeMillis()
    
    @Volatile
    private var isPaused: Boolean = false

    /**
     * الدالة الجوهرية لخنق الحزم بناءً على الرصيد المتاح بالملي ثانية
     */
    @Synchronized
    fun consume(bytesCount: Long) {
        // إذا كانت السرعة المحددة صفر أو أقل، نعتبره إيقاف كامل للإنترنت أو عدم تحديد (حسب منطق تطبيقك)
        // هنا سنعتبر أن 0 تعني سرعة غير محدودة لتفنيد المشاكل، أو يمكنك تعديلها
        if (refillRatePerSecond <= 0) return 

        while (true) {
            if (isPaused) {
                try {
                    (this as Object).wait(1000L)
                } catch (e: Exception) {
                    // تم إيقاظ الخيط
                }
                continue
            }

            refillTokens()
            
            if (tokens >= bytesCount) {
                tokens -= bytesCount
                break
            } else {
                // حساب مدة الانتظار المطلوبة بدقة كسرية بالملي ثانية لمنع الـ Packet Loss
                val bytesNeeded = bytesCount - tokens
                val millisecondsToWait = ((bytesNeeded / refillRatePerSecond) * 1000.0).toLong()
                
                // تأمين وقت انتظار لا يقل عن 1 ملي ثانية لعدم استهلاك المعالج بالـ Loop الطاحنة
                val waitTime = millisecondsToWait.coerceIn(1L, 500L)
                
                try {
                    (this as Object).wait(waitTime)
                } catch (e: Exception) {
                    // تم تحديث السرعة أو الاستيقاظ عبر notifyAll
                }
            }
        }
    }

    /**
     * إعادة تعبئة الخزان بالتوكنز بدقة Double لتفادي ضياع الحزم السريعة
     */
    private fun refillTokens() {
        val currentTime = System.currentTimeMillis()
        val elapsedTimeMs = currentTime - lastRefillTimestamp
        
        if (elapsedTimeMs > 0) {
            // الحساب بالـ Double يضمن أنه حتى لو مر 1 ملي ثانية، ستضاف كسور التوكنز بدقة
            val tokensToAdd = (elapsedTimeMs / 1000.0) * refillRatePerSecond
            if (tokensToAdd > 0) {
                tokens = min(capacity, tokens + tokensToAdd)
                lastRefillTimestamp = currentTime
            }
        }
    }

    /**
     * تحديث فوري وديناميكي عند سحب السلايدر وإيقاظ الخيوط المنتظرة فوراً
     */
    @Synchronized
    fun updateRate(newCapacity: Long, newRate: Long) {
        this.capacity = newCapacity.toDouble()
        this.refillRatePerSecond = newRate.toDouble()
        
        if (this.tokens > this.capacity) {
            this.tokens = this.capacity
        }
        
        // إعادة تعيين وقت التعبئة تجنباً لقفزات الوقت الفجائية
        this.lastRefillTimestamp = System.currentTimeMillis()
        
        Log.d("TokenBucket", "تم تحديث محرك التخنيق فوريًا: $newRate Bytes/Sec")
        
        // إيقاظ فوري لكافة الخيوط لتطبيق السرعة الجديدة بدون أي تهنيج
        (this as Object).notifyAll()
    }

    @Synchronized
    fun pause() {
        isPaused = true
        (this as Object).notifyAll()
    }

    @Synchronized
    fun resume() {
        isPaused = false
        lastRefillTimestamp = System.currentTimeMillis()
        (this as Object).notifyAll()
    }
}
