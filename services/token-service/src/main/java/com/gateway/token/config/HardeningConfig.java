package com.gateway.token.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateway.token.persistence.RateLimitStore;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Registers the security-hardening filters and the DynamoDB-backed rate-limit store for
 * token-service (task 2.4).
 *
 * <p>Filters are declared as beans (not {@code @Component}) so that ordering and dependencies are
 * explicit, consistent with the existing {@link InternalCallerConfig} pattern.
 */
@Configuration
public class HardeningConfig {

    @Bean
    public SecurityHeadersFilter securityHeadersFilter() {
        return new SecurityHeadersFilter();
    }

    @Bean
    public RateLimitStore rateLimitStore(
            DynamoDbClient dynamoDbClient, TokenProperties tokenProperties, Clock clock) {
        return new RateLimitStore(dynamoDbClient, tokenProperties.rateLimit(), clock);
    }

    @Bean
    public RateLimitFilter rateLimitFilter(
            RateLimitStore rateLimitStore,
            TokenProperties tokenProperties,
            ObjectMapper objectMapper) {
        return new RateLimitFilter(rateLimitStore, tokenProperties, objectMapper);
    }
}
