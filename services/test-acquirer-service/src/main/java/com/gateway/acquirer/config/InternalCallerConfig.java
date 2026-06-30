package com.gateway.acquirer.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateway.shared.security.InternalCallerAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class InternalCallerConfig {

    @Bean
    public InternalCallerAuthenticationFilter internalCallerAuthenticationFilter(
            AcquirerProperties properties, ObjectMapper objectMapper) {
        return new InternalCallerAuthenticationFilter(
                properties.internal().callers(), objectMapper);
    }
}
