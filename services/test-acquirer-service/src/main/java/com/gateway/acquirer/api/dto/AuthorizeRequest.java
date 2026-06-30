package com.gateway.acquirer.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AuthorizeRequest(
        @NotBlank String pan,
        @NotNull Integer expMonth,
        @NotNull Integer expYear,
        @NotBlank String cvv,
        @NotNull Long amount,
        @NotBlank String currency,
        @NotBlank String reference) {}
