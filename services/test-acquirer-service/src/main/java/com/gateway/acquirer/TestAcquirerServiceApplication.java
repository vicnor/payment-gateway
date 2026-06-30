package com.gateway.acquirer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TestAcquirerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TestAcquirerServiceApplication.class, args);
    }
}
