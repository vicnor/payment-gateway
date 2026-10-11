package com.gateway.token.config;

import com.gateway.shared.security.CallerConfig;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Token-service–specific configuration properties.
 *
 * <p>Bound from {@code gateway.token.*} in {@code application.yml}. Picked up automatically via
 * {@code @ConfigurationPropertiesScan} on {@link com.gateway.token.TokenServiceApplication}.
 */
@ConfigurationProperties("gateway.token")
public record TokenProperties(
        int ttlSeconds,
        CorsProperties cors,
        SessionSecretProperties sessionSecret,
        RateLimitProperties rateLimit,
        InternalProperties internal) {

    public TokenProperties {
        if (sessionSecret == null) {
            sessionSecret = new SessionSecretProperties(false, "http://localhost:8100", "");
        }
        if (internal == null) {
            internal = new InternalProperties(List.of());
        }
        if (rateLimit == null) {
            rateLimit = new RateLimitProperties(100, 1800);
        }
    }

    /** CORS configuration for the browser-facing checkout endpoint. */
    public record CorsProperties(List<String> allowedOrigins) {}

    /** Configuration for validating browser session secrets through checkout-service. */
    public record SessionSecretProperties(
            boolean enabled, String checkoutServiceBaseUrl, String internalToken) {}

    /**
     * Rate-limiting config for {@code POST /checkout/{session_id}/tokens}.
     *
     * <p>{@code maxAttempts} — maximum token-mint attempts allowed per session within the window.
     * {@code windowSeconds} — sliding window duration; also the DynamoDB TTL for counter items.
     */
    public record RateLimitProperties(int maxAttempts, long windowSeconds) {
        public RateLimitProperties {
            if (maxAttempts <= 0) maxAttempts = 100;
            if (windowSeconds <= 0) windowSeconds = 1800;
        }
    }

    /** Allowed internal service callers — each identified by id + pre-shared secret. */
    public record InternalProperties(List<CallerConfig> callers) {
        public InternalProperties {
            if (callers == null) callers = List.of();
        }
    }
}
