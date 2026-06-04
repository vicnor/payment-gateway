package com.gateway.shared.web.error;

import org.springframework.http.HttpStatus;

public final class ValidationException extends ApiException {

  public ValidationException(String code, String message, String param) {
    super("validation_error", code, message, param, HttpStatus.BAD_REQUEST);
  }

  public ValidationException(String code, String message) {
    super("validation_error", code, message, HttpStatus.BAD_REQUEST);
  }
}
