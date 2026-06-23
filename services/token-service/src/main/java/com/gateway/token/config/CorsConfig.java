package com.gateway.token.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS configuration for the browser-facing checkout endpoints.
 *
 * <p>Restricts {@code /checkout/**} to the configured allowed origins (localhost:3000 for local
 * development; production checkout domain via {@code application-prod.yml} or the {@code
 * gateway.token.cors.allowed-origins} property). Only {@code Content-Type} is permitted as a
 * non-simple request header (the browser sends it for JSON POSTs), and credentials are never
 * included. Preflight responses are cached for 1 hour to reduce OPTIONS chatter.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final TokenProperties tokenProperties;

    public CorsConfig(TokenProperties tokenProperties) {
        this.tokenProperties = tokenProperties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = tokenProperties.cors().allowedOrigins().toArray(new String[0]);
        registry.addMapping("/checkout/**")
                .allowedOrigins(origins)
                .allowedMethods("POST", "OPTIONS")
                .allowedHeaders("Content-Type")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
