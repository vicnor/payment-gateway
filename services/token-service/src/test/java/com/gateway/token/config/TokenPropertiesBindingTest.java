package com.gateway.token.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class TokenPropertiesBindingTest {

    @Test
    void bindsEveryCheckoutSessionAuthenticationProperty() {
        var source =
                new MapConfigurationPropertySource(
                        Map.of(
                                "gateway.token.session-secret.enabled", true,
                                "gateway.token.session-secret.checkout-service-base-url",
                                        "http://checkout.test:8100",
                                "gateway.token.session-secret.internal-token", "service-secret"));

        TokenProperties properties =
                new Binder(source).bind("gateway.token", Bindable.of(TokenProperties.class)).get();

        assertThat(properties.sessionSecret().enabled()).isTrue();
        assertThat(properties.sessionSecret().checkoutServiceBaseUrl())
                .isEqualTo("http://checkout.test:8100");
        assertThat(properties.sessionSecret().internalToken()).isEqualTo("service-secret");
    }
}
