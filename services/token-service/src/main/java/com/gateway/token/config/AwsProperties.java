package com.gateway.token.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AWS client configuration for token-service.
 *
 * <p>In production, {@code credentials} is absent (uses IAM instance role / default credential
 * chain). {@code dynamodb.endpoint} and {@code kms.endpoint} are also absent (SDK resolves regional
 * endpoints automatically). All three are set only in {@code local} and {@code test} profiles to
 * point at DynamoDB-Local and LocalStack respectively.
 */
@ConfigurationProperties("gateway.aws")
public record AwsProperties(
        String region,
        CredentialsProperties credentials,
        DynamoDbProperties dynamodb,
        KmsProperties kms) {

    /** Optional static credentials — set in local/test profiles only. */
    public record CredentialsProperties(String accessKey, String secretKey) {}

    /** DynamoDB endpoint override — absent in production. */
    public record DynamoDbProperties(String endpoint) {}

    /** KMS endpoint override and key alias/id. */
    public record KmsProperties(String endpoint, String keyId) {}
}
