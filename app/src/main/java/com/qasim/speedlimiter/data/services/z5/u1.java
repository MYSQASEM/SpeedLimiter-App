package com.qasim.speedlimiter.data.services.z5;

public final class u1 {

    public long maxBucketSize;
    public long tokensPerSecond;
    public boolean isPaused;
    public long availableTokens;
    public long lastRefillTime;

    public u1(long j7, long j8) {
        this.maxBucketSize = j7;
        this.tokensPerSecond = j8;
        this.availableTokens = j7;
        this.lastRefillTime = System.currentTimeMillis();
    }

    public final synchronized void a(long bytesToConsume) {
        long currentTokens;
        while (true) {
            if (this.isPaused) {
                try {
                    wait(60000L);
                } catch (Exception unused) {
                }
            } else {
                refillTokens();
                currentTokens = this.availableTokens;
                if (currentTokens >= bytesToConsume) {
                    break;
                }
                long sleepTime = ((bytesToConsume - currentTokens) * 1000) / this.tokensPerSecond;
                if (sleepTime < 1) {
                    sleepTime = 1;
                }
                try {
                    wait(sleepTime);
                } catch (InterruptedException unused2) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        this.availableTokens = currentTokens - bytesToConsume;
    }

    public final void refillTokens() {
        long now = System.currentTimeMillis();
        long timePassed = now - this.lastRefillTime;
        if (timePassed > 0) {
            long generatedTokens = (timePassed * this.tokensPerSecond) / 1000;
            if (generatedTokens > 0) {
                long newTokens = this.availableTokens + generatedTokens;
                long maxLimit = this.maxBucketSize;
                if (newTokens > maxLimit) {
                    newTokens = maxLimit;
                }
                this.availableTokens = newTokens;
                this.lastRefillTime = now;
            }
        }
    }

    public final synchronized void updateSpeedLimits(long maxSize, long perSecond) {
        this.maxBucketSize = maxSize;
        this.tokensPerSecond = perSecond;
        if (this.availableTokens > maxSize) {
            this.availableTokens = maxSize;
        }
        notifyAll();
    }
}
