package com.gateway.checkout.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gateway.aws")
public record AwsProperties(
        String region, CredentialsProperties credentials, DynamoDbProperties dynamodb) {

    public record CredentialsProperties(String accessKey, String secretKey) {}

    public record DynamoDbProperties(String endpoint) {}
}
