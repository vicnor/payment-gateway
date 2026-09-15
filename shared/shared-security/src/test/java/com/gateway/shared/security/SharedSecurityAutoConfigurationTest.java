package com.gateway.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.shared.security.cache.ApiKeyCandidateCache;
import com.gateway.shared.security.client.MerchantServiceClient;
import com.gateway.shared.security.config.SharedSecurityAutoConfiguration;
import com.gateway.shared.security.ratelimit.ApiKeyRateLimitFilter;
import com.gateway.shared.security.ratelimit.ApiKeyRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.crypto.password.PasswordEncoder;

class SharedSecurityAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner =
            new WebApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(
                                    SharedSecurityAutoConfiguration.class,
                                    JacksonAutoConfiguration.class,
                                    WebMvcAutoConfiguration.class));

    @Test
    void passwordEncoderBeanAlwaysRegistered() {
        contextRunner.run(ctx -> assertThat(ctx).hasSingleBean(PasswordEncoder.class));
    }

    @Test
    void clientAndCacheNotRegisteredWithoutBaseUrl() {
        contextRunner.run(
                ctx -> {
                    assertThat(ctx).doesNotHaveBean(MerchantServiceClient.class);
                    assertThat(ctx).doesNotHaveBean(ApiKeyCandidateCache.class);
                    assertThat(ctx).doesNotHaveBean(ApiKeyAuthenticationFilter.class);
                    assertThat(ctx).doesNotHaveBean(ApiKeyRateLimiter.class);
                    assertThat(ctx).doesNotHaveBean(ApiKeyRateLimitFilter.class);
                });
    }

    @Test
    void filterRegisteredWhenBaseUrlConfigured() {
        contextRunner
                .withPropertyValues(
                        "shared.security.merchant-service.base-url=http://merchant-service:8101")
                .run(
                        ctx -> {
                            assertThat(ctx).hasSingleBean(MerchantServiceClient.class);
                            assertThat(ctx).hasSingleBean(ApiKeyCandidateCache.class);
                            assertThat(ctx).hasSingleBean(ApiKeyAuthenticationFilter.class);
                            assertThat(ctx).hasSingleBean(ApiKeyRateLimiter.class);
                            assertThat(ctx).hasSingleBean(ApiKeyRateLimitFilter.class);
                        });
    }

    @Test
    void rateLimitingCanBeDisabledExplicitly() {
        contextRunner
                .withPropertyValues(
                        "shared.security.merchant-service.base-url=http://merchant-service:8101",
                        "shared.security.rate-limit.enabled=false")
                .run(
                        ctx -> {
                            assertThat(ctx).hasSingleBean(ApiKeyAuthenticationFilter.class);
                            assertThat(ctx).doesNotHaveBean(ApiKeyRateLimiter.class);
                            assertThat(ctx).doesNotHaveBean(ApiKeyRateLimitFilter.class);
                        });
    }

    @Test
    void rateLimitSettingsAreConfigurable() {
        contextRunner
                .withPropertyValues(
                        "shared.security.merchant-service.base-url=http://merchant-service:8101",
                        "shared.security.rate-limit.refill-per-second=7",
                        "shared.security.rate-limit.capacity=11",
                        "shared.security.rate-limit.idle-expiry=3m")
                .run(
                        ctx ->
                                assertThat(ctx.getBean(ApiKeyRateLimiter.class).capacity())
                                        .isEqualTo(11));
    }

    @Test
    void invalidRateLimitSettingsFailStartup() {
        contextRunner
                .withPropertyValues(
                        "shared.security.merchant-service.base-url=http://merchant-service:8101",
                        "shared.security.rate-limit.refill-per-second=0")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
