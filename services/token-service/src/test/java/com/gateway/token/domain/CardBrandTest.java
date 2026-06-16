package com.gateway.token.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class CardBrandTest {

    @Test
    void detectVisaFromLeadingFour() {
        assertThat(CardBrand.detect("4242424242424242")).contains(CardBrand.VISA);
    }

    @Test
    void detectVisaFromShortPan() {
        assertThat(CardBrand.detect("4111111111111")).contains(CardBrand.VISA);
    }

    @Test
    void detectMastercardFromClassicRange() {
        // 51–55 prefix
        assertThat(CardBrand.detect("5105105105105100")).contains(CardBrand.MASTERCARD);
        assertThat(CardBrand.detect("5500005555555559")).contains(CardBrand.MASTERCARD);
    }

    @Test
    void detectMastercardFromNewRange() {
        // 2221–2720 prefix
        assertThat(CardBrand.detect("2221000000000009")).contains(CardBrand.MASTERCARD);
        assertThat(CardBrand.detect("2720999999999999")).contains(CardBrand.MASTERCARD);
    }

    @Test
    void detectReturnsEmptyForAmex() {
        assertThat(CardBrand.detect("378282246310005")).isEmpty();
    }

    @Test
    void detectReturnsEmptyForDiscover() {
        assertThat(CardBrand.detect("6011111111111117")).isEmpty();
    }

    @Test
    void detectReturnsEmptyForBlankInput() {
        assertThat(CardBrand.detect("")).isEmpty();
        assertThat(CardBrand.detect(null)).isEmpty();
    }

    @Test
    void wireNameIsLowercase() {
        assertThat(CardBrand.VISA.wireName()).isEqualTo("visa");
        assertThat(CardBrand.MASTERCARD.wireName()).isEqualTo("mastercard");
    }

    @Test
    void cvvLengthIsThreeForBothBrands() {
        assertThat(CardBrand.VISA.cvvLength()).isEqualTo(3);
        assertThat(CardBrand.MASTERCARD.cvvLength()).isEqualTo(3);
    }

    @Test
    void detectReturnsOptionalOfVisa() {
        Optional<CardBrand> result = CardBrand.detect("4242424242424242");
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(CardBrand.VISA);
    }
}
