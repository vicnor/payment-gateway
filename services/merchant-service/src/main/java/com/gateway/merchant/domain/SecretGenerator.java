package com.gateway.merchant.domain;

import java.security.SecureRandom;
import java.util.Base64;

public final class SecretGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SECRET_BYTES = 32;

    private SecretGenerator() {}

    public static String generate() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
