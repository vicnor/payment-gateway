package com.gateway.checkout;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CheckoutServiceApplication {

    static void main(String[] args) {
        SpringApplication.run(CheckoutServiceApplication.class, args);
    }
}
