package com.gateway.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.shared.security.cache.ApiKeyCandidateCache;
import com.gateway.shared.security.client.MerchantServiceClient;
import com.gateway.shared.security.config.SharedSecurityAutoConfiguration;
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
                        });
    }
}
