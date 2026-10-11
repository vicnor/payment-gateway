package com.gateway.token.config;

import com.gateway.token.client.CheckoutSessionClient;
import com.gateway.token.client.HttpCheckoutSessionClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class CheckoutSessionClientConfig {
    @Bean
    CheckoutSessionClient checkoutSessionClient(TokenProperties properties) {
        var config = properties.sessionSecret();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return new HttpCheckoutSessionClient(
                RestClient.builder()
                        .baseUrl(config.checkoutServiceBaseUrl())
                        .requestFactory(factory)
                        .defaultHeader("X-Caller-Service", "token-service")
                        .defaultHeader("X-Internal-Token", config.internalToken())
                        .build());
    }
}
