package com.gateway.token;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TokenServiceApplication {

    static void main(String[] args) {
        SpringApplication.run(TokenServiceApplication.class, args);
    }
}
