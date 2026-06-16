package com.gateway.token.domain;

import java.util.Optional;

/**
 * Card brand detected from the PAN BIN range.
 *
 * <p>v1 scope is Visa and Mastercard only (per {@code docs/architecture/overview.md}). All other
 * PANs are rejected at the tokenize endpoint.
 */
public enum CardBrand {
    VISA {
        @Override
        public String wireName() {
            return "visa";
        }

        @Override
        public int cvvLength() {
            return 3;
        }
    },
    MASTERCARD {
        @Override
        public String wireName() {
            return "mastercard";
        }

        @Override
        public int cvvLength() {
            return 3;
        }
    };

    /** The lowercase string value sent to clients and stored in DynamoDB. */
    public abstract String wireName();

    /** Expected CVV digit length for this brand. */
    public abstract int cvvLength();

    /**
     * Detect the brand from the supplied PAN (digits only, 13–19 characters, already format-
     * validated by bean validation before reaching this method).
     *
     * <ul>
     *   <li>Visa: first digit {@code 4}
     *   <li>Mastercard: first two digits {@code 51}–{@code 55}, or first four digits {@code
     *       2221}–{@code 2720} (16-digit cards only)
     * </ul>
     *
     * @return the detected brand, or empty if not supported
     */
    public static Optional<CardBrand> detect(String pan) {
        if (pan == null || pan.isBlank()) {
            return Optional.empty();
        }
        if (pan.charAt(0) == '4') {
            return Optional.of(VISA);
        }
        if (pan.length() == 16) {
            int prefix2 = Integer.parseInt(pan.substring(0, 2));
            if (prefix2 >= 51 && prefix2 <= 55) {
                return Optional.of(MASTERCARD);
            }
            int prefix4 = Integer.parseInt(pan.substring(0, 4));
            if (prefix4 >= 2221 && prefix4 <= 2720) {
                return Optional.of(MASTERCARD);
            }
        }
        return Optional.empty();
    }
}
