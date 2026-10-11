package com.gateway.checkout.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateway.checkout.client.HttpPaymentClient;
import com.gateway.checkout.client.PaymentClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

@Configuration
public class PaymentClientConfig {
    @Bean
    PaymentClient paymentClient(CheckoutProperties properties, ObjectMapper mapper) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(40));
        RestClient client =
                RestClient.builder()
                        .baseUrl(properties.paymentService().baseUrl())
                        .requestFactory(factory)
                        .defaultHeader("X-Caller-Service", "checkout-service")
                        .defaultHeader(
                                "X-Internal-Token", properties.paymentService().internalToken())
                        .messageConverters(
                                cs -> {
                                    cs.removeIf(
                                            c -> c instanceof MappingJackson2HttpMessageConverter);
                                    cs.add(new MappingJackson2HttpMessageConverter(mapper));
                                })
                        .build();
        return new HttpPaymentClient(client);
    }
}
