package com.gateway.token.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Browser-submitted card data for the {@code POST /checkout/{session_id}/tokens} endpoint.
 *
 * <p>Annotations perform format-only validation (digit count, range). Semantic validation — Luhn
 * check, future expiry, brand-based CVV length — is handled by {@code CardValidator}.
 *
 * <p>Field names are camelCase in Java; Jackson's {@code SNAKE_CASE} strategy maps them to/from
 * {@code card_number}, {@code exp_month}, {@code exp_year}, {@code holder_name} in JSON.
 *
 * <p><strong>PCI rule:</strong> This record must never be logged or serialised into any response.
 */
public record TokenizeRequest(
        @NotBlank
                @Pattern(
                        regexp = "\\d{13,19}",
                        message = "Must be 13 to 19 digits with no spaces or separators")
                String cardNumber,
        @NotNull @Min(1) @Max(12) Integer expMonth,
        @NotNull @Min(1000) @Max(9999) Integer expYear,
        @NotBlank @Pattern(regexp = "\\d{3,4}", message = "Must be 3 or 4 digits") String cvv,
        @NotBlank @Size(max = 100) String holderName) {}
