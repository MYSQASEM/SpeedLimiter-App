package com.qasim.speedlimiter.utils

class TokenBucket(private val maxCapacity: Long, private val refillRatePerMs: Long) {
    // نستخدم تزامناً مرناً أو المتغيرات المتقلبة لحماية الخيوط المتعددة في الـ VPN
    private var tokens: Long = maxCapacity
    private var lastRefillTimestamp: Long = System.currentTimeMillis()

    /**
     * دالة الاستهلاك المعدلة:
     * لا تعيد true أو false، بل تحجز الحزمة وتنام (Sleep) حتى تتوفر مساحة لمرورها.
     */
    @Synchronized
    fun consume(amount: Long) {
        refill()

        // إذا كانت النقاط الحالية لا تكفي لحجم الحزمة، ندخل في حلقة انتظار
        while (tokens < amount) {
            try {
                // الانتظار لفترة قصيرة جداً (من 2 إلى 5 ملي ثانية) لمنع استهلاك المعالج CPU 100%
                Thread.sleep(3) 
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            // إعادة ملء السلة بعد الاستيقاظ لمعرفة هل توفرت نقاط جديدة؟
            refill()
        }

        // خصم النقاط بعد توفرها ومرور الحزمة بنجاح
        tokens -= amount
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
