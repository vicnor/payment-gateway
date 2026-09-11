package com.gateway.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AWS client configuration for payment-service.
 *
 * <p>In production, {@code credentials} is absent (uses IAM instance role / default credential
 * chain) and {@code sns.endpoint} is also absent (SDK resolves the regional endpoint
 * automatically). Both are set only in {@code local} and {@code test} profiles to point at
 * LocalStack.
 */
@ConfigurationProperties("gateway.aws")
public record AwsProperties(String region, CredentialsProperties credentials, SnsProperties sns) {

    /** Optional static credentials — set in local/test profiles only. */
    public record CredentialsProperties(String accessKey, String secretKey) {}

    /** SNS endpoint override — absent in production. */
    public record SnsProperties(String endpoint) {}
}
