package com.gateway.checkout.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CheckoutCorsConfig implements WebMvcConfigurer {
    private final CheckoutProperties properties;

    public CheckoutCorsConfig(CheckoutProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/checkout/**")
                .allowedOrigins(properties.allowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("Content-Type", "X-Checkout-Session-Secret")
                .exposedHeaders("X-Request-Id", "Retry-After")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
