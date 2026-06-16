package com.gateway.token.domain;

import com.gateway.shared.web.error.ValidationException;
import com.gateway.token.api.dto.TokenizeRequest;
import java.time.Clock;
import java.time.YearMonth;
import org.springframework.stereotype.Component;

/**
 * Validates incoming card data and detects the brand.
 *
 * <p>All validation failures throw {@link ValidationException} which the {@code
 * GlobalExceptionHandler} maps to a 400 error envelope.
 *
 * <p>Note: CVV is validated here but is never passed downstream — it is discarded immediately after
 * this method returns (PCI hard rule).
 */
@Component
public final class CardValidator {

    private final Clock clock;

    public CardValidator(Clock clock) {
        this.clock = clock;
    }

    /**
     * Validate the card fields and return the detected {@link CardBrand}.
     *
     * @throws ValidationException for any validation failure
     */
    public CardBrand validate(TokenizeRequest request) {
        if (!luhnCheck(request.cardNumber())) {
            throw new ValidationException(
                    "invalid_card_number", "Card number is invalid.", "card_number");
        }

        CardBrand brand =
                CardBrand.detect(request.cardNumber())
                        .orElseThrow(
                                () ->
                                        new ValidationException(
                                                "unsupported_card_brand",
                                                "Card brand is not supported. Only Visa and Mastercard are accepted.",
                                                "card_number"));

        if (request.cvv().length() != brand.cvvLength()) {
            throw new ValidationException(
                    "invalid_cvv", "CVV length is invalid for the detected card brand.", "cvv");
        }

        YearMonth expiry = YearMonth.of(request.expYear(), request.expMonth());
        YearMonth now = YearMonth.now(clock);
        if (!expiry.isAfter(now)) {
            throw new ValidationException("card_expired", "Card has expired.", "exp_month");
        }

        return brand;
    }

    /**
     * Luhn algorithm check.
     *
     * @return {@code true} if the PAN passes the check
     */
    static boolean luhnCheck(String pan) {
        int sum = 0;
        boolean alternate = false;
        for (int i = pan.length() - 1; i >= 0; i--) {
            int digit = pan.charAt(i) - '0';
            if (alternate) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            alternate = !alternate;
        }
        return sum % 10 == 0;
    }
}
