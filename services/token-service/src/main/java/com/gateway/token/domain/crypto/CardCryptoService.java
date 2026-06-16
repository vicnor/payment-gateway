package com.gateway.token.domain.crypto;

import com.gateway.token.domain.RawCard;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.EncryptRequest;

/**
 * Envelope-encrypts raw card data for PCI-compliant storage.
 *
 * <h3>Encryption model</h3>
 *
 * <ol>
 *   <li>Generate a 32-byte Data Encryption Key (DEK) using {@link SecureRandom}.
 *   <li>Call KMS {@code Encrypt} to wrap the DEK with the Customer Master Key — the CMK never
 *       leaves KMS.
 *   <li>AES-256-GCM–encrypt the card plaintext (PAN + expiry + holder name; CVV is NOT included)
 *       using the plain DEK with a random 12-byte IV.
 *   <li>Zero and discard the plain DEK from memory.
 *   <li>Return the base64 ciphertext and base64 encrypted DEK for persistence.
 * </ol>
 *
 * <p>The IV is prepended to the ciphertext+tag before base64-encoding so decryption (task 2.3) can
 * recover it: {@code base64(iv[12] || ciphertext+tag)}.
 *
 * <p><strong>PCI rule:</strong> This class must never log the PAN, CVV, plain DEK, or any
 * intermediate plaintext.
 */
@Service
public class CardCryptoService {

    private static final int DEK_LENGTH_BYTES = 32;
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final String AES_GCM = "AES/GCM/NoPadding";

    private final KmsClient kmsClient;
    private final SecureRandom secureRandom;

    public CardCryptoService(KmsClient kmsClient) {
        this.kmsClient = kmsClient;
        this.secureRandom = new SecureRandom();
    }

    /**
     * Encrypt the card data and return the {@link EncryptionResult} ready for persistence.
     *
     * @param card the raw card (PAN + expiry + holder name; CVV must already be discarded)
     * @param kmsKeyId the KMS CMK alias or key ID used to wrap the DEK
     */
    public EncryptionResult encrypt(RawCard card, String kmsKeyId) {
        byte[] dek = new byte[DEK_LENGTH_BYTES];
        secureRandom.nextBytes(dek);
        try {
            // Step 1: KMS-wrap the DEK (use the EncryptRequest overload so Mockito stubs work)
            EncryptRequest encryptRequest =
                    EncryptRequest.builder()
                            .keyId(kmsKeyId)
                            .plaintext(SdkBytes.fromByteArray(dek))
                            .build();
            SdkBytes encryptedDekBytes = kmsClient.encrypt(encryptRequest).ciphertextBlob();
            String encryptedDek =
                    Base64.getEncoder().encodeToString(encryptedDekBytes.asByteArray());

            // Step 2: AES-256-GCM encrypt the card payload
            String encryptedCardData = aesGcmEncrypt(dek, buildCardPayload(card));

            return new EncryptionResult(encryptedCardData, encryptedDek, kmsKeyId);
        } finally {
            // Step 3: zero the plain DEK — it must not linger in memory
            Arrays.fill(dek, (byte) 0);
        }
    }

    /**
     * AES-256-GCM encrypt. Package-private for unit testing.
     *
     * @param key 32-byte AES key
     * @param plaintext UTF-8 plaintext string
     * @return base64(iv[12] || ciphertext+tag)
     */
    static String aesGcmEncrypt(byte[] key, String plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));

            byte[] ciphertextAndTag = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // Prepend IV so the decryption side can recover it
            byte[] combined = new byte[GCM_IV_LENGTH_BYTES + ciphertextAndTag.length];
            System.arraycopy(iv, 0, combined, 0, GCM_IV_LENGTH_BYTES);
            System.arraycopy(
                    ciphertextAndTag, 0, combined, GCM_IV_LENGTH_BYTES, ciphertextAndTag.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM encryption failed", e);
        }
    }

    /**
     * AES-256-GCM decrypt. Package-private; used in task 2.3 (detokenize) and unit tests.
     *
     * @param key 32-byte AES key
     * @param ciphertext base64(iv[12] || ciphertext+tag)
     * @return decrypted plaintext
     */
    static String aesGcmDecrypt(byte[] key, String ciphertext) {
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertext);
            byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH_BYTES);
            byte[] ciphertextAndTag =
                    Arrays.copyOfRange(combined, GCM_IV_LENGTH_BYTES, combined.length);

            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));

            byte[] plainBytes = cipher.doFinal(ciphertextAndTag);
            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM decryption failed", e);
        }
    }

    /**
     * Serialize the card fields to encrypt. CVV is intentionally excluded.
     *
     * <p>Format: {@code {"pan":"...","exp_month":12,"exp_year":2027,"holder_name":"..."}}
     */
    private static String buildCardPayload(RawCard card) {
        return "{\"pan\":\""
                + card.pan()
                + "\",\"exp_month\":"
                + card.expMonth()
                + ",\"exp_year\":"
                + card.expYear()
                + ",\"holder_name\":\""
                + escapeJson(card.holderName())
                + "\"}";
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Result of a successful card encryption, ready to be persisted to DynamoDB.
     *
     * @param encryptedCardData base64(iv || AES-GCM ciphertext+tag) of the card payload
     * @param encryptedDek base64 of the KMS-wrapped DEK
     * @param kmsKeyId the KMS CMK used to wrap the DEK
     */
    public record EncryptionResult(
            String encryptedCardData, String encryptedDek, String kmsKeyId) {}
}
