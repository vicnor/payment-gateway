package com.gateway.token.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Basic CORS configuration for the browser-facing checkout endpoints.
 *
 * <p>Restricts {@code /checkout/**} to the configured allowed origins (localhost:3000 for local
 * development; add the production checkout domain via the {@code
 * gateway.token.cors.allowed-origins} property). Full hardening — preflight caching, CSP, no-cache,
 * X-Frame-Options — is task 2.4.
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
                .allowCredentials(false);
    }
}
