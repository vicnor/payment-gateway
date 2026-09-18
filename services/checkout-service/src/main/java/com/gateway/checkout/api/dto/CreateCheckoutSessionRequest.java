package com.gateway.checkout.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record CreateCheckoutSessionRequest(
        @NotNull Long amount,
        @NotBlank String currency,
        @NotBlank @Size(max = 255) String merchantReference,
        @NotBlank @Size(max = 2048) String returnUrl,
        @NotBlank @Size(max = 2048) String cancelUrl,
        @Valid Customer customer,
        @Size(max = 500) String description,
        String locale,
        @Size(max = 50) Map<String, String> metadata) {
    public record Customer(
            @Email @Size(max = 320) String email, @Size(max = 255) String reference) {}
}
