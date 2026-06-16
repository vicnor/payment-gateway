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
        InternalProperties internal) {

    public TokenProperties {
        if (internal == null) {
            internal = new InternalProperties(List.of());
        }
    }

    /** CORS configuration for the browser-facing checkout endpoint. */
    public record CorsProperties(List<String> allowedOrigins) {}

    /**
     * Session-secret authentication stub.
     *
     * <p>Disabled by default until checkout-service (Phase 5) is built and can validate secrets.
     */
    public record SessionSecretProperties(boolean enabled) {}

    /** Allowed internal service callers — each identified by id + pre-shared secret. */
    public record InternalProperties(List<CallerConfig> callers) {
        public InternalProperties {
            if (callers == null) callers = List.of();
        }
    }
}
