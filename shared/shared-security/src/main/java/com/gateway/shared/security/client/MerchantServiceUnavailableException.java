package com.gateway.shared.security.client;

public class MerchantServiceUnavailableException extends RuntimeException {

    public MerchantServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
