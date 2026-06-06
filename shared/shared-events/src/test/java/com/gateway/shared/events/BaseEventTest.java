package com.gateway.shared.events;

import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BaseEventTest {

    @Test
    void nowGeneratesEvtPrefixedId() {
        BaseEvent event = BaseEvent.now(EventType.PAYMENT_CAPTURED);
        assertNotNull(event.id());
        assertTrue(
                event.id().startsWith("evt_"),
                "Expected id to start with 'evt_', was: " + event.id());
        // ULID is 26 chars uppercase after the prefix
        String ulid = event.id().substring(4);
        assertEquals26UlidChars(ulid);
    }

    @Test
    void nowSetsCorrectType() {
        BaseEvent event = BaseEvent.now(EventType.CHECKOUT_COMPLETED);
        assertNotNull(event.type());
        assertSame(EventType.CHECKOUT_COMPLETED, event.type());
    }

    @Test
    void nowSetsCreatedCloseToNow() {
        Instant before = Instant.now();
        BaseEvent event = BaseEvent.now(EventType.PAYMENT_FAILED);
        Instant after = Instant.now();
        assertNotNull(event.created());
        assertFalse(event.created().isBefore(before), "created should be >= before");
        assertFalse(event.created().isAfter(after), "created should be <= after");
    }

    @Test
    void eachCallGeneratesUniqueId() {
        BaseEvent a = BaseEvent.now(EventType.PAYMENT_CAPTURED);
        BaseEvent b = BaseEvent.now(EventType.PAYMENT_CAPTURED);
        assertTrue(!a.id().equals(b.id()), "Each call should produce a unique ID");
    }

    private void assertEquals26UlidChars(String ulid) {
        assertEquals(26, ulid.length(), "ULID portion should be 26 chars, was: " + ulid.length());
        assertTrue(
                ulid.matches("[0-9A-HJKMNP-TV-Z]{26}"),
                "ULID should match Crockford base32 pattern");
    }
}
