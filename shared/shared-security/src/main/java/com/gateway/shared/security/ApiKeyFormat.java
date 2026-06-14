package com.gateway.shared.security;

import java.util.regex.Pattern;

public final class ApiKeyFormat {

    public static final int PREFIX_LENGTH = 16;

    /** Matches {@code sk_(test|live)_<26-char ULID>_<43-char base64url>}. */
    public static final Pattern PATTERN =
            Pattern.compile("^sk_(test|live)_[0-9A-HJKMNP-TV-Z]{26}_[A-Za-z0-9_-]{43}$");

    private ApiKeyFormat() {}

    public static boolean isValid(String key) {
        return key != null && PATTERN.matcher(key).matches();
    }

    public static String extractPrefix(String key) {
        return key.substring(0, PREFIX_LENGTH);
    }
}
