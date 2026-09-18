package com.gateway.checkout.client;

import com.fasterxml.jackson.annotation.JsonAlias;

public record MerchantConfig(
        String id,
        String name,
        @JsonAlias("return_url_pattern") String returnUrlPattern,
        @JsonAlias("cancel_url_pattern") String cancelUrlPattern,
        Branding branding,
        String mode,
        String status) {
    public record Branding(
            @JsonAlias("logo_url") String logoUrl, @JsonAlias("accent_color") String accentColor) {}
}
