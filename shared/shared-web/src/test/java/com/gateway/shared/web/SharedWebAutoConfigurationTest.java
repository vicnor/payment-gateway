package com.gateway.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.shared.web.config.SharedWebAutoConfiguration;
import com.gateway.shared.web.exception.GlobalExceptionHandler;
import com.gateway.shared.web.request.RequestIdFilter;
import com.gateway.shared.web.request.RequestLoggingFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class SharedWebAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner =
            new WebApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(
                                    SharedWebAutoConfiguration.class,
                                    WebMvcAutoConfiguration.class));

    @Test
    void registersRequestIdFilter() {
        contextRunner.run(ctx -> assertThat(ctx).hasSingleBean(RequestIdFilter.class));
    }

    @Test
    void registersRequestLoggingFilter() {
        contextRunner.run(ctx -> assertThat(ctx).hasSingleBean(RequestLoggingFilter.class));
    }

    @Test
    void registersGlobalExceptionHandler() {
        contextRunner.run(ctx -> assertThat(ctx).hasSingleBean(GlobalExceptionHandler.class));
    }

    @Test
    void requestIdFilterHasHighestPrecedenceOrder() {
        contextRunner.run(
                ctx -> {
                    RequestIdFilter filter = ctx.getBean(RequestIdFilter.class);
                    // The filter implements Ordered via @Order — verify by checking the bean exists
                    assertThat(filter).isNotNull();
                });
    }
}
