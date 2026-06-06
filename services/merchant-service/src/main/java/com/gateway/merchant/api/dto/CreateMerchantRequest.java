package com.gateway.merchant.api.dto;

import com.gateway.merchant.api.validation.MerchantUrlsValid;
import com.gateway.merchant.domain.Branding;
import com.gateway.merchant.domain.MerchantMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@MerchantUrlsValid
public record CreateMerchantRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank String callbackUrl,
        @NotBlank String returnUrlPattern,
        @NotBlank String cancelUrlPattern,
        @NotNull MerchantMode mode,
        Branding branding) {}
