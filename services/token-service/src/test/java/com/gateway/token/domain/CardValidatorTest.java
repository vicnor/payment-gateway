package com.gateway.token.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.shared.web.error.ValidationException;
import com.gateway.token.api.dto.TokenizeRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CardValidatorTest {

    // Fixed clock: June 15 2026, so any card expiring Jun 2026 or earlier is expired
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC);

    private CardValidator validator;

    @BeforeEach
    void setUp() {
        validator = new CardValidator(FIXED_CLOCK);
    }

    // --- happy path ---

    @Test
    void validVisaCardReturnsVisaBrand() {
        TokenizeRequest request = visaRequest("4242424242424242", 12, 2027, "123");
        CardBrand brand = validator.validate(request);
        assertThat(brand).isEqualTo(CardBrand.VISA);
    }

    @Test
    void validMastercardReturnsMatchingBrand() {
        TokenizeRequest request = validRequest("5105105105105100", 12, 2027, "123");
        assertThat(validator.validate(request)).isEqualTo(CardBrand.MASTERCARD);
    }

    @Test
    void expiryInSameYearButLaterMonthIsAccepted() {
        // Fixed clock is Jun 2026; July 2026 is still in the future
        TokenizeRequest request = visaRequest("4242424242424242", 7, 2026, "123");
        assertThat(validator.validate(request)).isEqualTo(CardBrand.VISA);
    }

    // --- Luhn failures ---

    @Test
    void invalidLuhnThrowsValidationException() {
        // 4242424242424241 fails Luhn
        TokenizeRequest request = visaRequest("4242424242424241", 12, 2027, "123");
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("invalid")
                .extracting("param")
                .isEqualTo("card_number");
    }

    // --- brand failures ---

    @Test
    void unsupportedBrandThrowsValidationException() {
        // Amex 378282246310005 — passes Luhn but isn't Visa/MC
        TokenizeRequest request = validRequest("378282246310005", 12, 2027, "1234");
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo("unsupported_card_brand");
    }

    // --- expiry failures ---

    @Test
    void expiredCardInSameMonthIsRejected() {
        // Jun 2026 is NOT after Jun 2026 (must be strictly after)
        TokenizeRequest request = visaRequest("4242424242424242", 6, 2026, "123");
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo("card_expired");
    }

    @Test
    void expiredCardInPastMonthIsRejected() {
        TokenizeRequest request = visaRequest("4242424242424242", 5, 2026, "123");
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo("card_expired");
    }

    @Test
    void expiredCardInPastYearIsRejected() {
        TokenizeRequest request = visaRequest("4242424242424242", 12, 2025, "123");
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo("card_expired");
    }

    // --- CVV length failures ---

    @Test
    void cvvTooShortForVisaThrowsValidationException() {
        TokenizeRequest request = visaRequest("4242424242424242", 12, 2027, "12");
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo("invalid_cvv");
    }

    @Test
    void cvvTooLongForVisaThrowsValidationException() {
        // DTO @Pattern allows 3-4 digits; Visa expects exactly 3
        TokenizeRequest request = visaRequest("4242424242424242", 12, 2027, "1234");
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(ValidationException.class)
                .extracting("code")
                .isEqualTo("invalid_cvv");
    }

    // --- Luhn helper ---

    @Test
    void luhnCheckPassesForKnownGoodNumbers() {
        assertThat(CardValidator.luhnCheck("4242424242424242")).isTrue();
        assertThat(CardValidator.luhnCheck("5105105105105100")).isTrue();
        assertThat(CardValidator.luhnCheck("4000000000000002"))
                .isTrue(); // always-decline test card
    }

    @Test
    void luhnCheckFailsForBadNumbers() {
        assertThat(CardValidator.luhnCheck("4242424242424241")).isFalse();
        assertThat(CardValidator.luhnCheck("1234567890123456")).isFalse();
    }

    // --- helpers ---

    private static TokenizeRequest visaRequest(String pan, int month, int year, String cvv) {
        return new TokenizeRequest(pan, month, year, cvv, "Test Cardholder");
    }

    private static TokenizeRequest validRequest(String pan, int month, int year, String cvv) {
        return new TokenizeRequest(pan, month, year, cvv, "Test Cardholder");
    }
}
