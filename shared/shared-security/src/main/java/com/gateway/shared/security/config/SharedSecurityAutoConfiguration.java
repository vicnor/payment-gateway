package com.gateway.shared.security.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateway.shared.security.ApiKeyAuthenticationFilter;
import com.gateway.shared.security.cache.ApiKeyCandidateCache;
import com.gateway.shared.security.client.HttpMerchantServiceClient;
import com.gateway.shared.security.client.MerchantServiceClient;
import java.time.Duration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.client.RestClient;

@AutoConfiguration
@ConditionalOnWebApplication
@EnableConfigurationProperties(SharedSecurityProperties.class)
public class SharedSecurityAutoConfiguration {

    @Bean
    @ConditionalOnProperty("shared.security.merchant-service.base-url")
    public MerchantServiceClient merchantServiceClient(SharedSecurityProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) Duration.ofSeconds(3).toMillis());
        requestFactory.setReadTimeout((int) Duration.ofSeconds(5).toMillis());

        RestClient restClient =
                RestClient.builder()
                        .baseUrl(properties.merchantService().baseUrl())
                        .requestFactory(requestFactory)
                        .build();
        return new HttpMerchantServiceClient(restClient);
    }

    @Bean
    @ConditionalOnBean(MerchantServiceClient.class)
    public ApiKeyCandidateCache apiKeyCandidateCache(
            MerchantServiceClient client, SharedSecurityProperties properties) {
        return new ApiKeyCandidateCache(client, properties.cache().ttl());
    }

    @Bean
    public PasswordEncoder apiKeyPasswordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    @ConditionalOnBean(ApiKeyCandidateCache.class)
    public ApiKeyAuthenticationFilter apiKeyAuthenticationFilter(
            ApiKeyCandidateCache cache,
            PasswordEncoder apiKeyPasswordEncoder,
            ObjectMapper objectMapper,
            SharedSecurityProperties properties) {
        return new ApiKeyAuthenticationFilter(
                cache, apiKeyPasswordEncoder, objectMapper, properties.skipPaths());
    }
}
