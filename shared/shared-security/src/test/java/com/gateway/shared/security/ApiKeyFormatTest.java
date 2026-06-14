package com.gateway.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ApiKeyFormatTest {

    static final String VALID_TEST_KEY =
            "sk_test_01JTESTAAAAAAA0000000000AA_localdevfixedkey000000000000000000000000000";
    static final String VALID_LIVE_KEY =
            "sk_live_01JTESTAAAAAAA0000000000AA_localdevfixedkey000000000000000000000000000";

    @Test
    void testModeKeyIsValid() {
        assertThat(ApiKeyFormat.isValid(VALID_TEST_KEY)).isTrue();
    }

    @Test
    void liveModeKeyIsValid() {
        assertThat(ApiKeyFormat.isValid(VALID_LIVE_KEY)).isTrue();
    }

    @Test
    void extractsPrefix() {
        assertThat(ApiKeyFormat.extractPrefix(VALID_TEST_KEY)).isEqualTo("sk_test_01JTESTA");
        assertThat(ApiKeyFormat.extractPrefix(VALID_TEST_KEY)).hasSize(ApiKeyFormat.PREFIX_LENGTH);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "sk_test_short",
                "pk_test_01JTESTAAAAAAA0000000000AA_localdevfixedkey000000000000000000000000000",
                "sk_staging_01JTESTAAAAAAA0000000000AA_localdevfixedkey000000000000000000000000000",
                "sk_test_INVALID_ULID_CHARS_I00000_localdevfixedkey000000000000000000000000000",
                "sk_test_01JTESTAAAAAAA0000000000AA_tooshort",
                "Bearer sk_test_01JTESTAAAAAAA0000000000AA_localdevfixedkey000000000000000000000000000",
            })
    void invalidKeysAreRejected(String key) {
        assertThat(ApiKeyFormat.isValid(key)).isFalse();
    }

    @Test
    void nullIsInvalid() {
        assertThat(ApiKeyFormat.isValid(null)).isFalse();
    }
}
