package com.gateway.shared.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EventTypeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @ParameterizedTest
    @CsvSource({
        "CHECKOUT_CREATED,checkout.created",
        "CHECKOUT_COMPLETED,checkout.completed",
        "CHECKOUT_CANCELLED,checkout.cancelled",
        "CHECKOUT_EXPIRED,checkout.expired",
        "PAYMENT_CAPTURED,payment.captured",
        "PAYMENT_FAILED,payment.failed"
    })
    void serializesToWireValue(String enumName, String wireValue) throws Exception {
        EventType type = EventType.valueOf(enumName);
        String json = objectMapper.writeValueAsString(type);
        assertEquals("\"" + wireValue + "\"", json);
    }

    @ParameterizedTest
    @CsvSource({
        "checkout.created,CHECKOUT_CREATED",
        "checkout.completed,CHECKOUT_COMPLETED",
        "checkout.cancelled,CHECKOUT_CANCELLED",
        "checkout.expired,CHECKOUT_EXPIRED",
        "payment.captured,PAYMENT_CAPTURED",
        "payment.failed,PAYMENT_FAILED"
    })
    void deserializesFromWireValue(String wireValue, String enumName) throws Exception {
        EventType result = objectMapper.readValue("\"" + wireValue + "\"", EventType.class);
        assertEquals(EventType.valueOf(enumName), result);
    }

    @Test
    void unknownWireValueThrows() {
        assertThrows(
                Exception.class,
                () -> objectMapper.readValue("\"unknown.event\"", EventType.class));
    }

    @ParameterizedTest
    @CsvSource({
        "checkout.created,CHECKOUT_CREATED",
        "payment.captured,PAYMENT_CAPTURED",
        "payment.failed,PAYMENT_FAILED"
    })
    void fromWireRoundtrip(String wire, String enumName) {
        assertEquals(EventType.valueOf(enumName), EventType.fromWire(wire));
        assertEquals(wire, EventType.valueOf(enumName).wireValue());
    }
}
