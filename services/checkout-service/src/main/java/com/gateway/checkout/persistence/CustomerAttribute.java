package com.gateway.checkout.persistence;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;

@DynamoDbBean
public class CustomerAttribute {
    private String email, reference;

    @DynamoDbAttribute("email")
    public String getEmail() {
        return email;
    }

    public void setEmail(String v) {
        email = v;
    }

    @DynamoDbAttribute("reference")
    public String getReference() {
        return reference;
    }

    public void setReference(String v) {
        reference = v;
    }
}
