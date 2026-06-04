package com.gateway.shared.events;

import com.github.f4b6a3.ulid.UlidCreator;
import java.time.Instant;

public record BaseEvent(String id, EventType type, Instant created) {

  public static BaseEvent now(EventType type) {
    return new BaseEvent("evt_" + UlidCreator.getUlid().toString(), type, Instant.now());
  }
}
