package com.gateway.merchant.config;

import com.gateway.merchant.domain.Argon2Hasher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

@Configuration
public class EncoderConfig {

    @Bean
    public Argon2PasswordEncoder argon2PasswordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    public Argon2Hasher argon2Hasher(Argon2PasswordEncoder encoder) {
        return new Argon2Hasher(encoder);
    }
}
