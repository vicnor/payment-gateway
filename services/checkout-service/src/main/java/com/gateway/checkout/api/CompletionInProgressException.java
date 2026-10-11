package com.gateway.checkout.api;

public final class CompletionInProgressException extends RuntimeException {
    private final String code;

    public CompletionInProgressException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
