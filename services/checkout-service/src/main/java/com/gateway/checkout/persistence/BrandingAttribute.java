package com.gateway.checkout.persistence;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;

@DynamoDbBean
public class BrandingAttribute {
    private String logoUrl, accentColor;

    @DynamoDbAttribute("logo_url")
    public String getLogoUrl() {
        return logoUrl;
    }

    public void setLogoUrl(String v) {
        logoUrl = v;
    }

    @DynamoDbAttribute("accent_color")
    public String getAccentColor() {
        return accentColor;
    }

    public void setAccentColor(String v) {
        accentColor = v;
    }
}
