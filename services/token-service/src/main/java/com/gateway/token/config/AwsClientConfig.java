package com.gateway.token.config;

import java.net.URI;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.KmsClientBuilder;

/**
 * Hand-wired AWS SDK v2 client beans for token-service.
 *
 * <p>No Spring Cloud AWS — this service manages its own client lifecycle to keep the PCI-scoped
 * dependency surface minimal and auditable.
 *
 * <p>Endpoint overrides are applied only when the corresponding property is set, so production runs
 * with SDK default endpoint resolution (regional HTTPS endpoints via IAM role).
 */
@Configuration
@EnableConfigurationProperties(AwsProperties.class)
public class AwsClientConfig {

    private final AwsProperties properties;

    public AwsClientConfig(AwsProperties properties) {
        this.properties = properties;
    }

    @Bean
    public DynamoDbClient dynamoDbClient() {
        DynamoDbClientBuilder builder =
                DynamoDbClient.builder()
                        .region(Region.of(properties.region()))
                        .credentialsProvider(credentialsProvider());

        if (properties.dynamodb() != null && properties.dynamodb().endpoint() != null) {
            builder.endpointOverride(URI.create(properties.dynamodb().endpoint()));
        }

        return builder.build();
    }

    @Bean
    public DynamoDbEnhancedClient dynamoDbEnhancedClient(DynamoDbClient dynamoDbClient) {
        return DynamoDbEnhancedClient.builder().dynamoDbClient(dynamoDbClient).build();
    }

    @Bean
    public KmsClient kmsClient() {
        KmsClientBuilder builder =
                KmsClient.builder()
                        .region(Region.of(properties.region()))
                        .credentialsProvider(credentialsProvider());

        if (properties.kms() != null && properties.kms().endpoint() != null) {
            builder.endpointOverride(URI.create(properties.kms().endpoint()));
        }

        return builder.build();
    }

    private AwsCredentialsProvider credentialsProvider() {
        AwsProperties.CredentialsProperties creds = properties.credentials();
        if (creds != null && creds.accessKey() != null && creds.secretKey() != null) {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(creds.accessKey(), creds.secretKey()));
        }
        return DefaultCredentialsProvider.create();
    }
}
