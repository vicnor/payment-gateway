package com.gateway.acquirer.config;

import com.gateway.shared.security.CallerConfig;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gateway.acquirer")
public record AcquirerProperties(InternalProperties internal, long timeoutSeconds) {

    public AcquirerProperties {
        if (internal == null) internal = new InternalProperties(List.of());
    }

    public record InternalProperties(List<CallerConfig> callers) {

        public InternalProperties {
            if (callers == null) callers = List.of();
        }
    }
}
