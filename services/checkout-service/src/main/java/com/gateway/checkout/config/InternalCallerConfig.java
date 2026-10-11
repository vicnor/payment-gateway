package com.gateway.checkout.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateway.shared.security.InternalCallerAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class InternalCallerConfig {
    @Bean
    InternalCallerAuthenticationFilter checkoutInternalCallerAuthenticationFilter(
            CheckoutProperties properties, ObjectMapper objectMapper) {
        return new InternalCallerAuthenticationFilter(properties.internalCallers(), objectMapper);
    }
}
