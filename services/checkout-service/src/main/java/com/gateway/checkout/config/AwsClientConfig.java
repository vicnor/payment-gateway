package com.gateway.checkout.config;

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

    private AwsCredentialsProvider credentialsProvider() {
        AwsProperties.CredentialsProperties credentials = properties.credentials();
        if (credentials != null
                && credentials.accessKey() != null
                && credentials.secretKey() != null) {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(credentials.accessKey(), credentials.secretKey()));
        }
        return DefaultCredentialsProvider.create();
    }
}
