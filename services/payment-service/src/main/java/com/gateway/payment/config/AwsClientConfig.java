package com.gateway.payment.config;

import java.net.URI;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.SnsClientBuilder;

/**
 * Hand-wired AWS SDK v2 client bean for payment-service.
 *
 * <p>No Spring Cloud AWS — this service manages its own client lifecycle, matching the pattern used
 * in token-service. Endpoint override is applied only when the corresponding property is set, so
 * production runs with SDK default endpoint resolution (regional HTTPS endpoint via IAM role).
 */
@Configuration
@EnableConfigurationProperties(AwsProperties.class)
public class AwsClientConfig {

    private final AwsProperties properties;

    public AwsClientConfig(AwsProperties properties) {
        this.properties = properties;
    }

    @Bean
    public SnsClient snsClient() {
        SnsClientBuilder builder =
                SnsClient.builder()
                        .region(Region.of(properties.region()))
                        .credentialsProvider(credentialsProvider());

        if (properties.sns() != null && properties.sns().endpoint() != null) {
            builder.endpointOverride(URI.create(properties.sns().endpoint()));
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
