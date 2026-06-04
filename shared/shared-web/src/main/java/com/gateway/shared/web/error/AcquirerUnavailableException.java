package com.gateway.shared.web.error;

import org.springframework.http.HttpStatus;

public final class AcquirerUnavailableException extends ApiException {

  public AcquirerUnavailableException(String message) {
    super("acquirer_unavailable", "acquirer_unavailable", message, HttpStatus.SERVICE_UNAVAILABLE);
  }

  public AcquirerUnavailableException() {
    this("The payment acquirer is currently unavailable. Please try again later.");
  }
}
