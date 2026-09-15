package com.gateway.shared.security.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("shared.security")
public record SharedSecurityProperties(
        MerchantServiceProperties merchantService,
        CacheProperties cache,
        List<String> skipPaths,
        RateLimitProperties rateLimit) {

    public SharedSecurityProperties {
        if (cache == null) {
            cache = new CacheProperties(Duration.ofMinutes(1));
        }
        if (skipPaths == null) {
            skipPaths = List.of("/actuator/**", "/internal/**");
        }
        if (rateLimit == null) {
            rateLimit = new RateLimitProperties(true, 100L, 200L, Duration.ofMinutes(10), null);
        }
    }

    public record MerchantServiceProperties(String baseUrl) {}

    public record CacheProperties(Duration ttl) {
        public CacheProperties {
            if (ttl == null) {
                ttl = Duration.ofMinutes(1);
            }
        }
    }

    public record RateLimitProperties(
            Boolean enabled,
            Long refillPerSecond,
            Long capacity,
            Duration idleExpiry,
            List<String> includePaths) {
        public RateLimitProperties {
            if (enabled == null) {
                enabled = true;
            }
            if (refillPerSecond == null) {
                refillPerSecond = 100L;
            } else if (refillPerSecond <= 0) {
                throw new IllegalArgumentException("rate-limit refill-per-second must be positive");
            }
            if (capacity == null) {
                capacity = 200L;
            } else if (capacity <= 0) {
                throw new IllegalArgumentException("rate-limit capacity must be positive");
            }
            if (idleExpiry == null) {
                idleExpiry = Duration.ofMinutes(10);
            } else if (idleExpiry.isZero() || idleExpiry.isNegative()) {
                throw new IllegalArgumentException("rate-limit idle-expiry must be positive");
            }
            if (includePaths == null) {
                includePaths = List.of("/v1/**");
            } else if (includePaths.isEmpty()) {
                throw new IllegalArgumentException("rate-limit include-paths must not be empty");
            }
        }
    }
}
