package com.gateway.payment.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * Request body for {@code POST /internal/v1/payments}.
 *
 * <p>Called by checkout-service after the consumer submits the card form and the browser has
 * obtained a single-use token from token-service.
 */
public record CreatePaymentRequest(
        @NotBlank String checkoutSessionId,
        @NotBlank String merchantId,
        @NotNull @Min(1) Long amount,
        @NotBlank
                @Size(min = 3, max = 3)
                @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter ISO 4217 code")
                String currency,
        @NotBlank String merchantReference,
        @NotBlank String token,
        Map<String, Object> metadata) {}
