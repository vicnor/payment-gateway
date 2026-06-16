package com.gateway.token.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateway.shared.security.InternalCallerAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the internal service-to-service authentication filter for {@code /internal/**}. */
@Configuration
public class InternalCallerConfig {

    @Bean
    public InternalCallerAuthenticationFilter internalCallerAuthenticationFilter(
            TokenProperties tokenProperties, ObjectMapper objectMapper) {
        return new InternalCallerAuthenticationFilter(
                tokenProperties.internal().callers(), objectMapper);
    }
}
