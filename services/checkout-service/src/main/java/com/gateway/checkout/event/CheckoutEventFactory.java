package com.gateway.checkout.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateway.checkout.persistence.CheckoutOutboxItem;
import com.gateway.checkout.persistence.CheckoutSessionItem;
import com.gateway.shared.events.CheckoutEvent;
import com.gateway.shared.events.EventType;
import com.github.f4b6a3.ulid.UlidCreator;
import org.springframework.stereotype.Component;

@Component
public class CheckoutEventFactory {
    private final ObjectMapper mapper;

    public CheckoutEventFactory(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public CheckoutOutboxItem create(
            CheckoutSessionItem session,
            EventType type,
            String status,
            String paymentId,
            long now) {
        String id = "evt_" + UlidCreator.getUlid();
        var snapshot =
                new CheckoutEvent.Session(
                        session.getSessionId(),
                        "checkout_session",
                        session.getMerchantId(),
                        status,
                        session.getAmount(),
                        session.getCurrency(),
                        session.getMerchantReference(),
                        paymentId,
                        session.getCreatedAt(),
                        now,
                        session.getExpiresAt(),
                        "COMPLETED".equals(status) ? now : null);
        var event =
                new CheckoutEvent(
                        id,
                        "event",
                        type,
                        now,
                        Boolean.TRUE.equals(session.getLivemode()),
                        new CheckoutEvent.Data(snapshot));
        var item = new CheckoutOutboxItem();
        item.setEventId(id);
        item.setCreatedAt(now);
        item.setAvailableAt(now);
        item.setPending("PENDING");
        try {
            item.setPayload(mapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize checkout event", e);
        }
        return item;
    }
}
