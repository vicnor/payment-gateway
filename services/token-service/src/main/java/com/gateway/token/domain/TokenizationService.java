package com.gateway.token.domain;

import com.gateway.token.api.dto.TokenizeRequest;
import com.gateway.token.config.AwsProperties;
import com.gateway.token.config.TokenProperties;
import com.gateway.token.domain.crypto.CardCryptoService;
import com.gateway.token.domain.crypto.CardCryptoService.EncryptionResult;
import com.gateway.token.persistence.CardAttribute;
import com.gateway.token.persistence.DataKeyItem;
import com.gateway.token.persistence.DataKeyStore;
import com.gateway.token.persistence.TokenItem;
import com.gateway.token.persistence.TokenStore;
import com.github.f4b6a3.ulid.UlidCreator;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the tokenize flow: validate → encrypt → persist → return safe token metadata.
 *
 * <p>The ordering of writes is intentional: the {@code data_keys} row is persisted first so the
 * encrypted DEK is always reachable from the {@code tokens} row.
 *
 * <p><strong>PCI rule:</strong> CVV is validated in {@link CardValidator} and then discarded — it
 * is never passed to this service or any downstream component. Only PAN, expiry, and holder name
 * are encrypted and stored.
 */
@Service
public class TokenizationService {

    private final CardValidator cardValidator;
    private final CardCryptoService cardCryptoService;
    private final TokenStore tokenStore;
    private final DataKeyStore dataKeyStore;
    private final AwsProperties awsProperties;
    private final TokenProperties tokenProperties;

    public TokenizationService(
            CardValidator cardValidator,
            CardCryptoService cardCryptoService,
            TokenStore tokenStore,
            DataKeyStore dataKeyStore,
            AwsProperties awsProperties,
            TokenProperties tokenProperties) {
        this.cardValidator = cardValidator;
        this.cardCryptoService = cardCryptoService;
        this.tokenStore = tokenStore;
        this.dataKeyStore = dataKeyStore;
        this.awsProperties = awsProperties;
        this.tokenProperties = tokenProperties;
    }

    /**
     * Tokenize the supplied card data.
     *
     * @param sessionId the checkout session ID from the URL path
     * @param request the card fields (CVV is validated here and immediately discarded)
     * @return a {@link TokenResult} with only the five PCI-safe fields
     */
    public TokenResult tokenize(String sessionId, TokenizeRequest request) {
        // 1. Validate and detect brand; CVV is checked here and goes no further
        CardBrand brand = cardValidator.validate(request);
        String last4 = request.cardNumber().substring(request.cardNumber().length() - 4);

        // 2. Envelope-encrypt card payload (PAN + expiry + holder name only, NO CVV)
        RawCard rawCard =
                new RawCard(
                        request.cardNumber(),
                        request.expMonth(),
                        request.expYear(),
                        request.holderName());
        EncryptionResult encrypted =
                cardCryptoService.encrypt(rawCard, awsProperties.kms().keyId());

        // 3. Generate DynamoDB IDs
        String dataKeyId = "dk_" + UlidCreator.getUlid();
        String tokenId = "tok_" + UlidCreator.getUlid();

        Instant now = Instant.now();
        long nowEpoch = now.getEpochSecond();
        long expiresAt = now.plusSeconds(tokenProperties.ttlSeconds()).getEpochSecond();

        // 4. Persist data key first (so the reference from tokens is always resolvable)
        DataKeyItem dataKeyItem = new DataKeyItem();
        dataKeyItem.setDataKeyId(dataKeyId);
        dataKeyItem.setEncryptedDek(encrypted.encryptedDek());
        dataKeyItem.setKmsKeyId(encrypted.kmsKeyId());
        dataKeyItem.setCreatedAt(nowEpoch);
        dataKeyStore.save(dataKeyItem);

        // 5. Persist token (merchant_id is null in v1 — set when checkout-service is built)
        CardAttribute cardAttr = new CardAttribute();
        cardAttr.setBrand(brand.wireName());
        cardAttr.setLast4(last4);
        cardAttr.setExpMonth(request.expMonth());
        cardAttr.setExpYear(request.expYear());

        TokenItem tokenItem = new TokenItem();
        tokenItem.setToken(tokenId);
        tokenItem.setSessionId(sessionId);
        tokenItem.setEncryptedCardData(encrypted.encryptedCardData());
        tokenItem.setDataKeyId(dataKeyId);
        tokenItem.setCard(cardAttr);
        tokenItem.setSingleUse(true);
        tokenItem.setUsed(false);
        tokenItem.setCreatedAt(nowEpoch);
        tokenItem.setExpiresAt(expiresAt);
        tokenStore.save(tokenItem);

        return new TokenResult(
                tokenId, brand.wireName(), last4, request.expMonth(), request.expYear());
    }
}
