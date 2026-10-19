package com.gateway.checkout.persistence;

import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.*;

@DynamoDbBean
public class CheckoutOutboxItem {
    public static final TableSchema<CheckoutOutboxItem> TABLE_SCHEMA =
            TableSchema.fromBean(CheckoutOutboxItem.class);
    private String eventId;
    private String payload;
    private String pending;
    private Long createdAt;
    private Long availableAt;
    private Long publishedAt;
    private Long deleteAt;

    @DynamoDbPartitionKey
    @DynamoDbAttribute("event_id")
    public String getEventId() {
        return eventId;
    }

    public void setEventId(String v) {
        eventId = v;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String v) {
        payload = v;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = {"pending-index", "pending-age-index"})
    public String getPending() {
        return pending;
    }

    public void setPending(String v) {
        pending = v;
    }

    @DynamoDbSecondarySortKey(indexNames = "pending-age-index")
    @DynamoDbAttribute("created_at")
    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long v) {
        createdAt = v;
    }

    @DynamoDbSecondarySortKey(indexNames = "pending-index")
    @DynamoDbAttribute("available_at")
    public Long getAvailableAt() {
        return availableAt;
    }

    public void setAvailableAt(Long v) {
        availableAt = v;
    }

    @DynamoDbAttribute("published_at")
    public Long getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Long v) {
        publishedAt = v;
    }

    @DynamoDbAttribute("delete_at")
    public Long getDeleteAt() {
        return deleteAt;
    }

    public void setDeleteAt(Long v) {
        deleteAt = v;
    }
}
