package com.gateway.shared.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum EventType {
  CHECKOUT_CREATED("checkout.created"),
  CHECKOUT_COMPLETED("checkout.completed"),
  CHECKOUT_CANCELLED("checkout.cancelled"),
  CHECKOUT_EXPIRED("checkout.expired"),
  PAYMENT_CAPTURED("payment.captured"),
  PAYMENT_FAILED("payment.failed");

  private final String wireValue;

  EventType(String wireValue) {
    this.wireValue = wireValue;
  }

  @JsonValue
  public String wireValue() {
    return wireValue;
  }

  @JsonCreator
  public static EventType fromWire(String value) {
    for (EventType type : values()) {
      if (type.wireValue.equals(value)) {
        return type;
      }
    }
    throw new IllegalArgumentException("Unknown event type: " + value);
  }
}
