package com.gateway.merchant.domain;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

public final class Argon2Hasher {

    private final Argon2PasswordEncoder encoder;

    public Argon2Hasher(Argon2PasswordEncoder encoder) {
        this.encoder = encoder;
    }

    public String hash(String plain) {
        return encoder.encode(plain);
    }

    public boolean matches(String plain, String hash) {
        return encoder.matches(plain, hash);
    }
}
