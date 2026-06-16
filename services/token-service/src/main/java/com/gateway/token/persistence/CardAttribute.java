package com.gateway.token.persistence;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;

/**
 * Nested DynamoDB attribute for the {@code card} object stored inside a {@code tokens} row.
 *
 * <p>Persists only the safe subset of card meta-data (brand, last4, expiry) — no PAN, no CVV. The
 * {@code country} and {@code funding} fields are reserved per the data model but remain null in v1
 * (no BIN lookup service in scope).
 */
@DynamoDbBean
public class CardAttribute {

    private String brand;
    private String last4;
    private Integer expMonth;
    private Integer expYear;
    private String country;
    private String funding;

    @DynamoDbAttribute("brand")
    public String getBrand() {
        return brand;
    }

    public void setBrand(String brand) {
        this.brand = brand;
    }

    @DynamoDbAttribute("last4")
    public String getLast4() {
        return last4;
    }

    public void setLast4(String last4) {
        this.last4 = last4;
    }

    @DynamoDbAttribute("exp_month")
    public Integer getExpMonth() {
        return expMonth;
    }

    public void setExpMonth(Integer expMonth) {
        this.expMonth = expMonth;
    }

    @DynamoDbAttribute("exp_year")
    public Integer getExpYear() {
        return expYear;
    }

    public void setExpYear(Integer expYear) {
        this.expYear = expYear;
    }

    @DynamoDbAttribute("country")
    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    @DynamoDbAttribute("funding")
    public String getFunding() {
        return funding;
    }

    public void setFunding(String funding) {
        this.funding = funding;
    }
}
