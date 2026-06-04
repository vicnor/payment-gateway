package com.gateway.shared.web.error;

import org.springframework.http.HttpStatus;

public final class AuthenticationException extends ApiException {

  public AuthenticationException(String message) {
    super("authentication_error", "authentication_error", message, HttpStatus.UNAUTHORIZED);
  }

  public AuthenticationException() {
    this("Authentication required.");
  }
}
