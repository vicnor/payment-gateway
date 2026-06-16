package com.gateway.token.domain;

import com.gateway.shared.web.error.ConflictException;
import com.gateway.shared.web.error.NotFoundException;
import com.gateway.token.domain.crypto.CardCryptoService;
import com.gateway.token.persistence.DataKeyItem;
import com.gateway.token.persistence.DataKeyStore;
import com.gateway.token.persistence.TokenItem;
import com.gateway.token.persistence.TokenStore;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DecryptRequest;

/**
 * Orchestrates the detokenize flow for internal callers (payment-service only in v1).
 *
 * <p>Sequence: fetch token → check expiry → atomic single-use mark → fetch DEK → KMS decrypt →
 * AES-GCM decrypt → return PAN + expiry.
 *
 * <p><strong>PCI rules:</strong>
 *
 * <ul>
 *   <li>The plain DEK is zeroed immediately after the card payload is decrypted.
 *   <li>The PAN must never appear in any log line at any level.
 *   <li>Every successful call is written to the {@code com.gateway.token.audit} logger.
 * </ul>
 */
@Service
public class DetokenizationService {

    private static final Logger AUDIT = LoggerFactory.getLogger("com.gateway.token.audit");

    private final TokenStore tokenStore;
    private final DataKeyStore dataKeyStore;
    private final KmsClient kmsClient;
    private final Clock clock;

    public DetokenizationService(
            TokenStore tokenStore, DataKeyStore dataKeyStore, KmsClient kmsClient, Clock clock) {
        this.tokenStore = tokenStore;
        this.dataKeyStore = dataKeyStore;
        this.kmsClient = kmsClient;
        this.clock = clock;
    }

    /**
     * Detokenize the given token on behalf of the identified caller.
     *
     * @param tokenId the opaque token id (e.g. {@code tok_01HQX...})
     * @param callerId the authenticated caller service id, used only for the audit log
     * @return PAN and expiry extracted from the encrypted card payload
     * @throws NotFoundException if the token does not exist or has expired
     * @throws ConflictException if the token has already been used
     */
    public DetokenizeResult detokenize(String tokenId, String callerId) {
        TokenItem token =
                tokenStore
                        .findById(tokenId)
                        .orElseThrow(() -> new NotFoundException("Token", tokenId));

        if (token.getExpiresAt() <= clock.instant().getEpochSecond()) {
            throw new NotFoundException("Token", tokenId);
        }

        try {
            tokenStore.markUsed(tokenId);
        } catch (ConditionalCheckFailedException e) {
            throw new ConflictException("token_already_used", "Token has already been used.");
        }

        DataKeyItem dataKey = dataKeyStore.findById(token.getDataKeyId());
        byte[] dek = kmsDecrypt(dataKey.getEncryptedDek());
        try {
            String payload = CardCryptoService.aesGcmDecrypt(dek, token.getEncryptedCardData());
            DetokenizeResult result = parseCardPayload(payload);
            audit(tokenId, callerId);
            return result;
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    private byte[] kmsDecrypt(String encryptedDekBase64) {
        byte[] encryptedDek = Base64.getDecoder().decode(encryptedDekBase64);
        DecryptRequest request =
                DecryptRequest.builder()
                        .ciphertextBlob(SdkBytes.fromByteArray(encryptedDek))
                        .build();
        return kmsClient.decrypt(request).plaintext().asByteArray();
    }

    private static DetokenizeResult parseCardPayload(String json) {
        String pan = extractJsonString(json, "pan");
        int expMonth = extractJsonInt(json, "exp_month");
        int expYear = extractJsonInt(json, "exp_year");
        return new DetokenizeResult(pan, expMonth, expYear);
    }

    private static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search) + search.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }

    private static int extractJsonInt(String json, String key) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search) + search.length();
        int end = json.indexOf(',', start);
        if (end == -1) end = json.indexOf('}', start);
        return Integer.parseInt(json.substring(start, end).trim());
    }

    private void audit(String tokenId, String callerId) {
        AUDIT.info(
                "{\"event\":\"detokenize\",\"token_id\":\""
                        + tokenId
                        + "\",\"caller\":\""
                        + callerId
                        + "\",\"timestamp\":"
                        + clock.instant().getEpochSecond()
                        + "}");
    }
}
