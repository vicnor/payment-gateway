package com.gateway.shared.security.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ApiKeyRateLimiterTest {

    private final MutableTicker ticker = new MutableTicker();

    @Test
    void permitsInitialBurstThenRejects() {
        ApiKeyRateLimiter limiter = limiter(100, 200);

        ApiKeyRateLimiter.Decision decision = null;
        for (int request = 0; request < 200; request++) {
            decision = limiter.tryAcquire("key-a");
            assertThat(decision.allowed()).isTrue();
        }

        assertThat(decision.remaining()).isZero();
        assertThat(decision.resetSeconds()).isEqualTo(2);
        ApiKeyRateLimiter.Decision rejected = limiter.tryAcquire("key-a");
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.remaining()).isZero();
        assertThat(rejected.resetSeconds()).isEqualTo(2);
        assertThat(rejected.retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void refillsContinuouslyAtSustainedRate() {
        ApiKeyRateLimiter limiter = limiter(100, 1);
        assertThat(limiter.tryAcquire("key-a").allowed()).isTrue();

        for (int request = 0; request < 500; request++) {
            ticker.advance(Duration.ofMillis(10));
            assertThat(limiter.tryAcquire("key-a").allowed()).isTrue();
        }
    }

    @Test
    void fractionalRefillDoesNotPermitRequestUntilOneTokenExists() {
        ApiKeyRateLimiter limiter = limiter(100, 1);
        limiter.tryAcquire("key-a");

        ticker.advance(Duration.ofMillis(5));
        assertThat(limiter.tryAcquire("key-a").allowed()).isFalse();

        ticker.advance(Duration.ofMillis(5));
        assertThat(limiter.tryAcquire("key-a").allowed()).isTrue();
    }

    @Test
    void isolatesBucketsByApiKeyId() {
        ApiKeyRateLimiter limiter = limiter(1, 1);

        assertThat(limiter.tryAcquire("key-a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("key-a").allowed()).isFalse();
        assertThat(limiter.tryAcquire("key-b").allowed()).isTrue();
    }

    @Test
    void concurrentRequestsCannotExceedCapacity() throws Exception {
        ApiKeyRateLimiter limiter = limiter(100, 200);
        List<Future<Boolean>> results = new ArrayList<>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int request = 0; request < 1_000; request++) {
                results.add(executor.submit(() -> limiter.tryAcquire("key-a").allowed()));
            }
        }

        long allowed = 0;
        for (Future<Boolean> result : results) {
            if (result.get()) {
                allowed++;
            }
        }
        assertThat(allowed).isEqualTo(200);
    }

    @Test
    void expiresIdleBucketsWithoutChangingEffectiveQuota() {
        ApiKeyRateLimiter limiter = new ApiKeyRateLimiter(1, 2, Duration.ofMinutes(10), ticker);
        limiter.tryAcquire("key-a");
        limiter.tryAcquire("key-a");
        assertThat(limiter.estimatedBucketCount()).isEqualTo(1);

        ticker.advance(Duration.ofMinutes(11));
        assertThat(limiter.estimatedBucketCount()).isZero();

        ApiKeyRateLimiter.Decision recreated = limiter.tryAcquire("key-a");
        assertThat(recreated.allowed()).isTrue();
        assertThat(recreated.remaining()).isEqualTo(1);
    }

    private ApiKeyRateLimiter limiter(long refillPerSecond, long capacity) {
        return new ApiKeyRateLimiter(refillPerSecond, capacity, Duration.ofMinutes(10), ticker);
    }

    private static final class MutableTicker implements Ticker {

        private final AtomicLong nanos = new AtomicLong();

        @Override
        public long read() {
            return nanos.get();
        }

        void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }
    }
}
