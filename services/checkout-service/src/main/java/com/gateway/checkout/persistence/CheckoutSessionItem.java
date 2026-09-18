package com.gateway.checkout.persistence;

import java.util.List;
import java.util.Map;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;

@DynamoDbBean
public class CheckoutSessionItem {
    public static final String MERCHANT_CREATED_INDEX = "merchant-created-index";
    public static final TableSchema<CheckoutSessionItem> TABLE_SCHEMA =
            TableSchema.fromBean(CheckoutSessionItem.class);
    private String sessionId,
            sessionSecretHash,
            idempotencyStorageKey,
            merchantId,
            merchantReference,
            currency,
            status;
    private String paymentId, returnUrl, cancelUrl, selectedPaymentMethod, description, locale;
    private Long amount, createdAt, updatedAt, completedAt, expiresAt, deleteAt;
    private Boolean livemode;
    private List<String> availablePaymentMethods;
    private CustomerAttribute customer;
    private BrandingAttribute branding;
    private Map<String, String> metadata;

    @DynamoDbPartitionKey
    @DynamoDbAttribute("session_id")
    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String v) {
        sessionId = v;
    }

    @DynamoDbAttribute("session_secret_hash")
    public String getSessionSecretHash() {
        return sessionSecretHash;
    }

    public void setSessionSecretHash(String v) {
        sessionSecretHash = v;
    }

    @DynamoDbAttribute("idempotency_storage_key")
    public String getIdempotencyStorageKey() {
        return idempotencyStorageKey;
    }

    public void setIdempotencyStorageKey(String v) {
        idempotencyStorageKey = v;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = MERCHANT_CREATED_INDEX)
    @DynamoDbAttribute("merchant_id")
    public String getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(String v) {
        merchantId = v;
    }

    @DynamoDbAttribute("merchant_reference")
    public String getMerchantReference() {
        return merchantReference;
    }

    public void setMerchantReference(String v) {
        merchantReference = v;
    }

    @DynamoDbAttribute("amount")
    public Long getAmount() {
        return amount;
    }

    public void setAmount(Long v) {
        amount = v;
    }

    @DynamoDbAttribute("currency")
    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String v) {
        currency = v;
    }

    @DynamoDbAttribute("status")
    public String getStatus() {
        return status;
    }

    public void setStatus(String v) {
        status = v;
    }

    @DynamoDbAttribute("payment_id")
    public String getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(String v) {
        paymentId = v;
    }

    @DynamoDbAttribute("return_url")
    public String getReturnUrl() {
        return returnUrl;
    }

    public void setReturnUrl(String v) {
        returnUrl = v;
    }

    @DynamoDbAttribute("cancel_url")
    public String getCancelUrl() {
        return cancelUrl;
    }

    public void setCancelUrl(String v) {
        cancelUrl = v;
    }

    @DynamoDbAttribute("available_payment_methods")
    public List<String> getAvailablePaymentMethods() {
        return availablePaymentMethods;
    }

    public void setAvailablePaymentMethods(List<String> v) {
        availablePaymentMethods = v;
    }

    @DynamoDbAttribute("selected_payment_method")
    public String getSelectedPaymentMethod() {
        return selectedPaymentMethod;
    }

    public void setSelectedPaymentMethod(String v) {
        selectedPaymentMethod = v;
    }

    @DynamoDbAttribute("customer")
    public CustomerAttribute getCustomer() {
        return customer;
    }

    public void setCustomer(CustomerAttribute v) {
        customer = v;
    }

    @DynamoDbAttribute("description")
    public String getDescription() {
        return description;
    }

    public void setDescription(String v) {
        description = v;
    }

    @DynamoDbAttribute("locale")
    public String getLocale() {
        return locale;
    }

    public void setLocale(String v) {
        locale = v;
    }

    @DynamoDbAttribute("branding")
    public BrandingAttribute getBranding() {
        return branding;
    }

    public void setBranding(BrandingAttribute v) {
        branding = v;
    }

    @DynamoDbAttribute("metadata")
    public Map<String, String> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, String> v) {
        metadata = v;
    }

    @DynamoDbAttribute("livemode")
    public Boolean getLivemode() {
        return livemode;
    }

    public void setLivemode(Boolean v) {
        livemode = v;
    }

    @DynamoDbSecondarySortKey(indexNames = MERCHANT_CREATED_INDEX)
    @DynamoDbAttribute("created_at")
    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long v) {
        createdAt = v;
    }

    @DynamoDbAttribute("updated_at")
    public Long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Long v) {
        updatedAt = v;
    }

    @DynamoDbAttribute("completed_at")
    public Long getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Long v) {
        completedAt = v;
    }

    @DynamoDbAttribute("expires_at")
    public Long getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Long v) {
        expiresAt = v;
    }

    @DynamoDbAttribute("delete_at")
    public Long getDeleteAt() {
        return deleteAt;
    }

    public void setDeleteAt(Long v) {
        deleteAt = v;
    }
}
