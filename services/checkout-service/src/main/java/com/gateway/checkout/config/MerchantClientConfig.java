package com.gateway.checkout.config;

import com.gateway.checkout.client.HttpMerchantConfigClient;
import com.gateway.checkout.client.MerchantConfigClient;
import com.gateway.shared.security.config.SharedSecurityProperties;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class MerchantClientConfig {
    @Bean
    MerchantConfigClient merchantConfigClient(SharedSecurityProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return new HttpMerchantConfigClient(
                RestClient.builder()
                        .baseUrl(properties.merchantService().baseUrl())
                        .requestFactory(factory)
                        .build());
    }
}
