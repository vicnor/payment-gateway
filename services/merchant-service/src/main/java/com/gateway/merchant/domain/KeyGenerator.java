package com.gateway.merchant.domain;

import com.github.f4b6a3.ulid.UlidCreator;
import java.security.SecureRandom;
import java.util.Base64;

public final class KeyGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int RANDOM_BYTES = 32;

    private KeyGenerator() {}

    public static String generate(MerchantMode mode) {
        String ulid = UlidCreator.getUlid().toString();
        byte[] randomBytes = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(randomBytes);
        String suffix = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        return "sk_" + mode.name().toLowerCase() + "_" + ulid + "_" + suffix;
    }

    public static String keyPrefix(String key) {
        return key.substring(0, 16);
    }
}
