package com.gateway.shared.web.error;

import org.springframework.http.HttpStatus;

public final class RateLimitException extends ApiException {

  public RateLimitException() {
    super(
        "rate_limit_error",
        "rate_limit_exceeded",
        "Rate limit exceeded.",
        HttpStatus.TOO_MANY_REQUESTS);
  }
}
