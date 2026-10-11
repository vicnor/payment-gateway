package com.gateway.checkout.config;

import com.gateway.shared.security.CallerConfig;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gateway.checkout")
public record CheckoutProperties(
        String baseUrl,
        Duration sessionLifetime,
        Duration retention,
        long maximumAmount,
        PaymentClient paymentService,
        List<String> allowedOrigins,
        List<CallerConfig> internalCallers) {
    public CheckoutProperties {
        if (baseUrl == null) baseUrl = "https://checkout.yourgateway.com";
        if (sessionLifetime == null) sessionLifetime = Duration.ofMinutes(30);
        if (retention == null) retention = Duration.ofDays(30);
        if (maximumAmount == 0) maximumAmount = 99_999_999L;
        if (paymentService == null) paymentService = new PaymentClient("http://localhost:8102", "");
        if (allowedOrigins == null) allowedOrigins = List.of("http://localhost:3000");
        if (internalCallers == null) internalCallers = List.of();
    }

    public record PaymentClient(String baseUrl, String internalToken) {}
}
