package com.gateway.payment.config;

import com.gateway.shared.security.CallerConfig;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Payment-service–specific configuration properties.
 *
 * <p>Bound from {@code gateway.payment.*} in {@code application.yml}. Picked up automatically via
 * {@code @ConfigurationPropertiesScan} on {@link com.gateway.payment.PaymentServiceApplication}.
 */
@ConfigurationProperties("gateway.payment")
public record PaymentProperties(
        ClientProperties tokenService,
        ClientProperties acquirerService,
        InternalProperties internal) {

    public PaymentProperties {
        if (internal == null) {
            internal = new InternalProperties(List.of());
        }
    }

    /**
     * HTTP client config for a downstream internal service.
     *
     * <p>{@code baseUrl} — e.g. {@code http://localhost:8103}. {@code internalToken} — the
     * pre-shared secret sent as {@code X-Internal-Token}; this service's identity is always {@code
     * payment-service}.
     */
    public record ClientProperties(String baseUrl, String internalToken) {}

    /** Allowed inbound internal callers for {@code /internal/**} endpoints on this service. */
    public record InternalProperties(List<CallerConfig> callers) {
        public InternalProperties {
            if (callers == null) callers = List.of();
        }
    }
}
