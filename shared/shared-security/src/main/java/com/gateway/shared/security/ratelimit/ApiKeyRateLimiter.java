package com.gateway.shared.security.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.Objects;

/** A concurrency-safe, in-process token-bucket limiter partitioned by API-key id. */
public final class ApiKeyRateLimiter {

    private static final long TOKEN_SCALE = 1_000_000_000L;

    private final long refillPerSecond;
    private final long capacity;
    private final long capacityUnits;
    private final Ticker ticker;
    private final Cache<String, TokenBucket> buckets;

    public ApiKeyRateLimiter(
            long refillPerSecond, long capacity, Duration idleExpiry, Ticker ticker) {
        if (refillPerSecond <= 0) {
            throw new IllegalArgumentException("refillPerSecond must be positive");
        }
        if (capacity <= 0 || capacity > Long.MAX_VALUE / TOKEN_SCALE) {
            throw new IllegalArgumentException("capacity must be positive and supported");
        }
        if (idleExpiry == null || idleExpiry.isZero() || idleExpiry.isNegative()) {
            throw new IllegalArgumentException("idleExpiry must be positive");
        }
        this.refillPerSecond = refillPerSecond;
        this.capacity = capacity;
        this.capacityUnits = capacity * TOKEN_SCALE;
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.buckets = Caffeine.newBuilder().expireAfterAccess(idleExpiry).ticker(ticker).build();
    }

    public Decision tryAcquire(String apiKeyId) {
        Objects.requireNonNull(apiKeyId, "apiKeyId");
        long now = ticker.read();
        TokenBucket bucket = buckets.get(apiKeyId, ignored -> new TokenBucket(capacityUnits, now));
        return bucket.tryAcquire(now);
    }

    public long capacity() {
        return capacity;
    }

    long estimatedBucketCount() {
        buckets.cleanUp();
        return buckets.estimatedSize();
    }

    public record Decision(
            boolean allowed, long remaining, long resetSeconds, long retryAfterSeconds) {}

    private final class TokenBucket {

        private long availableUnits;
        private long lastRefillNanos;

        private TokenBucket(long availableUnits, long createdAtNanos) {
            this.availableUnits = availableUnits;
            this.lastRefillNanos = createdAtNanos;
        }

        private synchronized Decision tryAcquire(long now) {
            refill(now);
            boolean allowed = availableUnits >= TOKEN_SCALE;
            if (allowed) {
                availableUnits -= TOKEN_SCALE;
            }

            long remaining = availableUnits / TOKEN_SCALE;
            long resetNanos = ceilDiv(capacityUnits - availableUnits, refillPerSecond);
            long resetSeconds = ceilDiv(resetNanos, TOKEN_SCALE);
            long retryAfterSeconds =
                    allowed
                            ? 0
                            : ceilDiv(
                                    ceilDiv(TOKEN_SCALE - availableUnits, refillPerSecond),
                                    TOKEN_SCALE);
            return new Decision(allowed, remaining, resetSeconds, retryAfterSeconds);
        }

        private void refill(long now) {
            long elapsed = now - lastRefillNanos;
            if (elapsed <= 0 || availableUnits == capacityUnits) {
                lastRefillNanos = now;
                return;
            }

            long deficit = capacityUnits - availableUnits;
            long nanosToFull = ceilDiv(deficit, refillPerSecond);
            if (elapsed >= nanosToFull) {
                availableUnits = capacityUnits;
            } else {
                // Since elapsed is below nanosToFull, this multiplication cannot exceed deficit.
                availableUnits += elapsed * refillPerSecond;
            }
            lastRefillNanos = now;
        }
    }

    private static long ceilDiv(long dividend, long divisor) {
        return dividend / divisor + (dividend % divisor == 0 ? 0 : 1);
    }
}
