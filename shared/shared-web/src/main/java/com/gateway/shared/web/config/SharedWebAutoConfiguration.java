package com.gateway.shared.web.config;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.gateway.shared.web.exception.GlobalExceptionHandler;
import com.gateway.shared.web.request.RequestIdFilter;
import com.gateway.shared.web.request.RequestLoggingFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnWebApplication
public class SharedWebAutoConfiguration {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer gatewayJacksonCustomizer() {
        return builder ->
                builder.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                        .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Bean
    public RequestIdFilter requestIdFilter() {
        return new RequestIdFilter();
    }

    @Bean
    public RequestLoggingFilter requestLoggingFilter() {
        return new RequestLoggingFilter();
    }

    @Bean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }
}
