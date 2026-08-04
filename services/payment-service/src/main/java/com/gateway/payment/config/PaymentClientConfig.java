package com.gateway.payment.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateway.payment.client.AcquirerClient;
import com.gateway.payment.client.HttpAcquirerClient;
import com.gateway.payment.client.HttpTokenServiceClient;
import com.gateway.payment.client.TokenServiceClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

/**
 * Wires HTTP clients for downstream services.
 *
 * <p>Both clients send {@code X-Caller-Service: payment-service} and the pre-shared {@code
 * X-Internal-Token} as default headers — required by the downstream {@code
 * InternalCallerAuthenticationFilter}.
 *
 * <p>Timeouts are intentionally conservative: the acquirer read timeout exceeds the 30-second
 * test-card sleep so the server-side 504 is returned before the client cuts the connection.
 *
 * <p>The application {@link ObjectMapper} (SNAKE_CASE, from shared-web auto-config) is injected so
 * that downstream responses ({@code auth_code}, {@code exp_month}, etc.) deserialise correctly into
 * the camelCase Java records used by the client implementations.
 */
@Configuration
public class PaymentClientConfig {

    @Bean
    public TokenServiceClient tokenServiceClient(
            PaymentProperties properties, ObjectMapper objectMapper) {
        RestClient restClient =
                restClientBuilder(
                                properties.tokenService().baseUrl(),
                                Duration.ofSeconds(3),
                                Duration.ofSeconds(10),
                                properties.tokenService().internalToken(),
                                objectMapper)
                        .build();
        return new HttpTokenServiceClient(restClient);
    }

    @Bean
    public AcquirerClient acquirerClient(PaymentProperties properties, ObjectMapper objectMapper) {
        // Read timeout is 35s — longer than the 30s test-card sleep so the acquirer can return
        // its own 504 rather than the client dropping the connection first.
        RestClient restClient =
                restClientBuilder(
                                properties.acquirerService().baseUrl(),
                                Duration.ofSeconds(3),
                                Duration.ofSeconds(35),
                                properties.acquirerService().internalToken(),
                                objectMapper)
                        .build();
        return new HttpAcquirerClient(restClient);
    }

    private static RestClient.Builder restClientBuilder(
            String baseUrl,
            Duration connectTimeout,
            Duration readTimeout,
            String internalToken,
            ObjectMapper objectMapper) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(clientFactory(connectTimeout, readTimeout))
                .defaultHeader("X-Caller-Service", "payment-service")
                .defaultHeader("X-Internal-Token", internalToken)
                .messageConverters(
                        converters -> {
                            converters.removeIf(
                                    c -> c instanceof MappingJackson2HttpMessageConverter);
                            converters.add(new MappingJackson2HttpMessageConverter(objectMapper));
                        });
    }

    private static SimpleClientHttpRequestFactory clientFactory(
            Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) connectTimeout.toMillis());
        factory.setReadTimeout((int) readTimeout.toMillis());
        return factory;
    }
}
