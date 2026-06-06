package com.gateway.merchant.domain;

public record Branding(String logoUrl, String accentColor) {

    public static Branding empty() {
        return new Branding(null, null);
    }
}
