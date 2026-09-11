package com.gateway.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the transactional outbox → SNS publisher (task 4.3). */
@ConfigurationProperties("gateway.payment.outbox")
public record OutboxProperties(String topicArn, Publisher publisher) {

    public record Publisher(boolean enabled, int batchSize) {

        public Publisher {
            if (batchSize <= 0) {
                batchSize = 50;
            }
        }
    }
}
