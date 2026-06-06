package com.gateway.merchant.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KeyGeneratorTest {

    @Test
    void generatedTestKeyMatchesExpectedFormat() {
        String key = KeyGenerator.generate(MerchantMode.TEST);

        assertThat(key).matches("^sk_test_[0-9A-HJKMNP-TV-Z]{26}_[A-Za-z0-9_-]+$");
    }

    @Test
    void generatedLiveKeyMatchesExpectedFormat() {
        String key = KeyGenerator.generate(MerchantMode.LIVE);

        assertThat(key).matches("^sk_live_[0-9A-HJKMNP-TV-Z]{26}_[A-Za-z0-9_-]+$");
    }

    @Test
    void keyPrefixIsFirst16Characters() {
        String key = KeyGenerator.generate(MerchantMode.TEST);

        assertThat(KeyGenerator.keyPrefix(key)).isEqualTo(key.substring(0, 16));
        assertThat(KeyGenerator.keyPrefix(key)).hasSize(16);
    }

    @Test
    void twoGeneratedKeysAreUnique() {
        String first = KeyGenerator.generate(MerchantMode.TEST);
        String second = KeyGenerator.generate(MerchantMode.TEST);

        assertThat(first).isNotEqualTo(second);
    }
}
