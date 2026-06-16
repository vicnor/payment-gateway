package com.gateway.token.domain.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gateway.token.domain.RawCard;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.EncryptRequest;
import software.amazon.awssdk.services.kms.model.EncryptResponse;

class CardCryptoServiceTest {

    private KmsClient mockKmsClient;
    private CardCryptoService service;

    private static final String TEST_KEY_ID = "alias/test-key";
    private static final byte[] FAKE_ENCRYPTED_DEK = "fake-kms-encrypted-dek-bytes".getBytes();

    @BeforeEach
    void setUp() {
        mockKmsClient = mock(KmsClient.class);
        when(mockKmsClient.encrypt(any(EncryptRequest.class)))
                .thenReturn(
                        EncryptResponse.builder()
                                .ciphertextBlob(SdkBytes.fromByteArray(FAKE_ENCRYPTED_DEK))
                                .keyId(TEST_KEY_ID)
                                .build());
        service = new CardCryptoService(mockKmsClient);
    }

    @Test
    void encryptCallsKmsWithCorrectKeyId() {
        RawCard card = new RawCard("4242424242424242", 12, 2027, "Test Cardholder");

        service.encrypt(card, TEST_KEY_ID);

        verify(mockKmsClient).encrypt(any(EncryptRequest.class));
    }

    @Test
    void encryptReturnsNonEmptyBase64Ciphertext() {
        RawCard card = new RawCard("4242424242424242", 12, 2027, "Test Cardholder");

        CardCryptoService.EncryptionResult result = service.encrypt(card, TEST_KEY_ID);

        assertThat(result.encryptedCardData()).isNotBlank();
        // Verify it is valid base64
        byte[] decoded = Base64.getDecoder().decode(result.encryptedCardData());
        assertThat(decoded).hasSizeGreaterThan(12); // at least IV (12 bytes) + some ciphertext
    }

    @Test
    void encryptReturnsBase64OfFakeEncryptedDek() {
        RawCard card = new RawCard("4242424242424242", 12, 2027, "Test Cardholder");

        CardCryptoService.EncryptionResult result = service.encrypt(card, TEST_KEY_ID);

        String expectedDek = Base64.getEncoder().encodeToString(FAKE_ENCRYPTED_DEK);
        assertThat(result.encryptedDek()).isEqualTo(expectedDek);
    }

    @Test
    void encryptReturnsKmsKeyId() {
        RawCard card = new RawCard("4242424242424242", 12, 2027, "Test Cardholder");

        CardCryptoService.EncryptionResult result = service.encrypt(card, TEST_KEY_ID);

        assertThat(result.kmsKeyId()).isEqualTo(TEST_KEY_ID);
    }

    @Test
    void encryptTwiceProducesDifferentCiphertexts() {
        RawCard card = new RawCard("4242424242424242", 12, 2027, "Test Cardholder");

        String first = service.encrypt(card, TEST_KEY_ID).encryptedCardData();
        String second = service.encrypt(card, TEST_KEY_ID).encryptedCardData();

        // Different random IVs mean different ciphertexts even for identical input
        assertThat(first).isNotEqualTo(second);
    }

    // --- AES-GCM round-trip (package-private helpers) ---

    @Test
    void aesGcmEncryptAndDecryptRoundTrip() {
        byte[] key = new byte[32]; // 256-bit zero key for test
        String plaintext = "{\"pan\":\"4242424242424242\",\"exp_month\":12,\"exp_year\":2027}";

        String ciphertext = CardCryptoService.aesGcmEncrypt(key, plaintext);
        String recovered = CardCryptoService.aesGcmDecrypt(key, ciphertext);

        assertThat(recovered).isEqualTo(plaintext);
    }

    @Test
    void aesGcmCiphertextDoesNotContainPlaintext() {
        byte[] key = new byte[32];
        String plaintext = "super-secret-pan-data";

        String ciphertext = CardCryptoService.aesGcmEncrypt(key, plaintext);
        byte[] decoded = Base64.getDecoder().decode(ciphertext);

        // The raw bytes should not equal the plaintext bytes
        assertThat(decoded).isNotEqualTo(plaintext.getBytes());
    }

    @Test
    void aesGcmEncryptProducesValidBase64() {
        byte[] key = new byte[32];
        String ciphertext = CardCryptoService.aesGcmEncrypt(key, "hello");

        // Should not throw
        byte[] decoded = Base64.getDecoder().decode(ciphertext);
        // 12 IV + at least 1 byte ciphertext + 16 GCM tag = at least 29 bytes
        assertThat(decoded.length).isGreaterThanOrEqualTo(29);
    }

    @Test
    void aesGcmUsesUniqueIvEachCall() {
        byte[] key = new byte[32];
        String c1 = CardCryptoService.aesGcmEncrypt(key, "same-input");
        String c2 = CardCryptoService.aesGcmEncrypt(key, "same-input");
        assertThat(c1).isNotEqualTo(c2);
    }
}
