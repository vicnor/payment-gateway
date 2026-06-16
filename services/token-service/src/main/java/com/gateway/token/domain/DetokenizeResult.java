package com.gateway.token.domain;

/**
 * The decrypted card data returned to the caller of the detokenize endpoint.
 *
 * <p>CVV is not included — it is never persisted. Holder name is not included — not required by the
 * acquirer for authorization.
 */
public record DetokenizeResult(String pan, int expMonth, int expYear) {}
