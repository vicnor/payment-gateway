package com.gateway.checkout.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gateway.checkout")
public record CheckoutProperties(
        String baseUrl, Duration sessionLifetime, Duration retention, long maximumAmount) {
    public CheckoutProperties {
        if (baseUrl == null) baseUrl = "https://checkout.yourgateway.com";
        if (sessionLifetime == null) sessionLifetime = Duration.ofMinutes(30);
        if (retention == null) retention = Duration.ofDays(30);
        if (maximumAmount == 0) maximumAmount = 99_999_999L;
    }
}
