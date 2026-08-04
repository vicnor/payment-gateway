package com.gateway.acquirer.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Authorize request sent by payment-service to the acquirer.
 *
 * <p>{@code cvv} is optional. In the current single-use-token flow the CVV is not retained after
 * validation (PCI DSS prohibits post-auth CVV storage), so payment-service cannot forward it. When
 * real-acquirer integration is added the CVV will be carried transiently in the single-use token
 * and returned by detokenize — at that point this field will be populated. See ADR-0001.
 */
public record AuthorizeRequest(
        @NotBlank String pan,
        @NotNull Integer expMonth,
        @NotNull Integer expYear,
        String cvv,
        @NotNull Long amount,
        @NotBlank String currency,
        @NotBlank String reference) {}
