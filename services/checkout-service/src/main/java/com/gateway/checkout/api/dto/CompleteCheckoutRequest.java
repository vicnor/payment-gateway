package com.gateway.checkout.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CompleteCheckoutRequest(
        @NotBlank @Pattern(regexp = "tok_[0-9A-HJKMNP-TV-Z]{26}", message = "must be a valid token")
                String token) {}
