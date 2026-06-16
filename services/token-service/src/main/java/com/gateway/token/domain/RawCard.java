package com.gateway.token.domain;

/**
 * Transient, in-memory holder for raw card data within a single tokenize operation.
 *
 * <p><strong>PCI rule:</strong> CVV is NOT included — it is discarded immediately after {@link
 * CardValidator#validate} returns and is never passed to encryption or persistence. Only PAN,
 * expiry, and holder name are encrypted and stored.
 *
 * <p>Instances of this record must not be logged, serialized to any response, or stored in any form
 * other than the encrypted ciphertext produced by {@link
 * com.gateway.token.domain.crypto.CardCryptoService}.
 */
public record RawCard(String pan, int expMonth, int expYear, String holderName) {}
