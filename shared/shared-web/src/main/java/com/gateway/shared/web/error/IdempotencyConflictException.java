package com.gateway.shared.web.error;

import org.springframework.http.HttpStatus;

public final class IdempotencyConflictException extends ApiException {

  public IdempotencyConflictException() {
    super(
        "idempotency_key_conflict",
        "idempotency_key_conflict",
        "An idempotency key was reused with a different request body.",
        HttpStatus.CONFLICT);
  }
}
