package com.gateway.shared.security.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("shared.security")
public record SharedSecurityProperties(
        MerchantServiceProperties merchantService, CacheProperties cache, List<String> skipPaths) {

    public SharedSecurityProperties {
        if (cache == null) {
            cache = new CacheProperties(Duration.ofMinutes(1));
        }
        if (skipPaths == null) {
            skipPaths = List.of("/actuator/**", "/internal/**");
        }
    }

    public record MerchantServiceProperties(String baseUrl) {}

    public record CacheProperties(Duration ttl) {
        public CacheProperties {
            if (ttl == null) {
                ttl = Duration.ofMinutes(1);
            }
        }
    }
}
