package com.gateway.acquirer.config;

import com.gateway.acquirer.domain.AcquirerService;
import com.gateway.acquirer.domain.RequestLog;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DomainConfig {

    @Bean
    public RequestLog requestLog() {
        return new RequestLog();
    }

    @Bean
    public AcquirerService acquirerService(RequestLog requestLog, AcquirerProperties properties) {
        return new AcquirerService(requestLog, properties.timeoutSeconds() * 1000L);
    }
}
