package com.gateway.merchant.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SecretGeneratorTest {

    @Test
    void generatedSecretMatchesExpectedFormat() {
        String secret = SecretGenerator.generate();

        assertThat(secret).matches("^whsec_[A-Za-z0-9_-]+$");
    }

    @Test
    void generatedSecretHasSufficientLength() {
        String secret = SecretGenerator.generate();
        String encoded = secret.substring("whsec_".length());

        // 32 bytes base64url-no-padding = ceil(32 * 4/3) = 43 chars
        assertThat(encoded).hasSizeGreaterThanOrEqualTo(43);
    }

    @Test
    void twoGeneratedSecretsAreUnique() {
        String first = SecretGenerator.generate();
        String second = SecretGenerator.generate();

        assertThat(first).isNotEqualTo(second);
    }
}
