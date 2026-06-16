package com.gateway.token.api.dto;

/** Response returned by the detokenize endpoint. Contains PAN and expiry only — no CVV. */
public record DetokenizeResponse(String pan, int expMonth, int expYear) {}
