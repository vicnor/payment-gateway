package com.gateway.merchant.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.merchant.MerchantServiceApplication;
import com.gateway.merchant.api.dto.CreateMerchantRequest;
import com.gateway.merchant.api.dto.IssueApiKeyResponse;
import com.gateway.merchant.api.dto.MerchantResponse;
import com.gateway.merchant.api.dto.RotateWebhookSecretResponse;
import com.gateway.merchant.domain.Branding;
import com.gateway.merchant.domain.Merchant;
import com.gateway.merchant.domain.MerchantMode;
import com.gateway.merchant.domain.MerchantStatus;
import com.gateway.merchant.domain.WebhookSecret;
import com.gateway.merchant.persistence.ApiKeyRepository;
import com.gateway.merchant.persistence.MerchantRepository;
import com.gateway.merchant.persistence.WebhookSecretRepository;
import com.gateway.shared.testing.AbstractPostgresIT;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

@SpringBootTest(
        classes = MerchantServiceApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class MerchantAdminIT extends AbstractPostgresIT {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private ApiKeyRepository apiKeyRepository;
    @Autowired private WebhookSecretRepository webhookSecretRepository;
    @Autowired private Argon2PasswordEncoder argon2PasswordEncoder;

    @Test
    void createMerchantHappyPathPersistsAndReturns201() {
        CreateMerchantRequest request =
                new CreateMerchantRequest(
                        "Acme Corp",
                        "https://acme.example.com/webhook",
                        "^https://acme\\.example\\.com/.*$",
                        "^https://acme\\.example\\.com/cancel$",
                        MerchantMode.TEST,
                        new Branding("https://acme.example.com/logo.png", "#FF5733"));

        ResponseEntity<MerchantResponse> response =
                restTemplate.postForEntity("/admin/merchants", request, MerchantResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isNotNull();

        String id = response.getBody().id();
        assertThat(id).matches("^mer_[0-9A-HJKMNP-TV-Z]{26}$");

        Merchant persisted = merchantRepository.findById(id).orElseThrow();
        assertThat(persisted.getName()).isEqualTo("Acme Corp");
        assertThat(persisted.getCallbackUrl()).isEqualTo("https://acme.example.com/webhook");
        assertThat(persisted.getReturnUrlPattern()).isEqualTo("^https://acme\\.example\\.com/.*$");
        assertThat(persisted.getCancelUrlPattern())
                .isEqualTo("^https://acme\\.example\\.com/cancel$");
        assertThat(persisted.getMode()).isEqualTo(MerchantMode.TEST);
        assertThat(persisted.getStatus()).isEqualTo(MerchantStatus.ACTIVE);
        assertThat(persisted.getBranding().logoUrl())
                .isEqualTo("https://acme.example.com/logo.png");
        assertThat(persisted.getBranding().accentColor()).isEqualTo("#FF5733");
        assertThat(persisted.getCreatedAt()).isNotNull();
        assertThat(persisted.getUpdatedAt()).isNotNull();
    }

    @Test
    void issueApiKeyHappyPathReturnsKeyAndHashesCorrectly() {
        String merchantId = createTestMerchant().id();

        ResponseEntity<IssueApiKeyResponse> response =
                restTemplate.postForEntity(
                        "/admin/merchants/" + merchantId + "/api-keys",
                        null,
                        IssueApiKeyResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        IssueApiKeyResponse body = response.getBody();
        assertThat(body.key()).matches("^sk_test_[0-9A-HJKMNP-TV-Z]{26}_[A-Za-z0-9_-]+$");
        assertThat(body.keyPrefix()).isEqualTo(body.key().substring(0, 16));
        assertThat(body.mode()).isEqualTo(MerchantMode.TEST);
        assertThat(body.id()).isNotNull();

        var persistedKey = apiKeyRepository.findById(body.id()).orElseThrow();
        assertThat(persistedKey.getKeyPrefix()).isEqualTo(body.key().substring(0, 16));
        assertThat(argon2PasswordEncoder.matches(body.key(), persistedKey.getKeyHash())).isTrue();
        assertThat(argon2PasswordEncoder.matches("wrong-key", persistedKey.getKeyHash())).isFalse();
    }

    @Test
    void issueApiKeyForInactiveMerchantReturns409() {
        String merchantId = createTestMerchant().id();
        Merchant merchant = merchantRepository.findById(merchantId).orElseThrow();
        merchant.setStatus(MerchantStatus.SUSPENDED);
        merchantRepository.save(merchant);

        ResponseEntity<String> response =
                restTemplate.postForEntity(
                        "/admin/merchants/" + merchantId + "/api-keys", null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("\"type\":\"conflict_error\"");
    }

    @Test
    void issueApiKeyForUnknownMerchantReturns404() {
        ResponseEntity<String> response =
                restTemplate.postForEntity(
                        "/admin/merchants/mer_DOESNOTEXIST00000000000000/api-keys",
                        null,
                        String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("\"type\":\"not_found\"");
    }

    @Test
    void rotateWebhookSecretHardRotationDeactivatesPreviousSecret() {
        String merchantId = createTestMerchant().id();

        ResponseEntity<RotateWebhookSecretResponse> first =
                restTemplate.postForEntity(
                        "/admin/merchants/" + merchantId + "/webhook-secret",
                        null,
                        RotateWebhookSecretResponse.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        String firstSecret = first.getBody().secret();
        assertThat(firstSecret).matches("^whsec_[A-Za-z0-9_-]+$");

        ResponseEntity<RotateWebhookSecretResponse> second =
                restTemplate.postForEntity(
                        "/admin/merchants/" + merchantId + "/webhook-secret",
                        null,
                        RotateWebhookSecretResponse.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        String secondSecret = second.getBody().secret();
        assertThat(secondSecret).isNotEqualTo(firstSecret);

        List<WebhookSecret> inactive =
                webhookSecretRepository.findByMerchantIdAndActive(merchantId, false);
        assertThat(inactive).hasSize(1);
        assertThat(inactive.get(0).getRotatedAt()).isNotNull();

        var firstPersisted = webhookSecretRepository.findById(first.getBody().id()).orElseThrow();
        assertThat(firstPersisted.isActive()).isFalse();
        assertThat(firstPersisted.getRotatedAt()).isNotNull();
        assertThat(argon2PasswordEncoder.matches(firstSecret, firstPersisted.getSecretHash()))
                .isTrue();

        var secondPersisted = webhookSecretRepository.findById(second.getBody().id()).orElseThrow();
        assertThat(secondPersisted.isActive()).isTrue();
        assertThat(argon2PasswordEncoder.matches(secondSecret, secondPersisted.getSecretHash()))
                .isTrue();
    }

    @Test
    void rotateWebhookSecretForUnknownMerchantReturns404() {
        ResponseEntity<String> response =
                restTemplate.postForEntity(
                        "/admin/merchants/mer_DOESNOTEXIST00000000000000/webhook-secret",
                        null,
                        String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("\"type\":\"not_found\"");
    }

    @Test
    void plainValuesNeverAppearInLogs(CapturedOutput output) {
        String merchantId = createTestMerchant().id();

        ResponseEntity<IssueApiKeyResponse> keyResponse =
                restTemplate.postForEntity(
                        "/admin/merchants/" + merchantId + "/api-keys",
                        null,
                        IssueApiKeyResponse.class);
        String plainKey = keyResponse.getBody().key();

        ResponseEntity<RotateWebhookSecretResponse> secretResponse =
                restTemplate.postForEntity(
                        "/admin/merchants/" + merchantId + "/webhook-secret",
                        null,
                        RotateWebhookSecretResponse.class);
        String plainSecret = secretResponse.getBody().secret();

        String capturedLog = output.getAll();
        assertThat(capturedLog).doesNotContain(plainKey);
        assertThat(capturedLog).doesNotContain(plainSecret);
    }

    private MerchantResponse createTestMerchant() {
        CreateMerchantRequest request =
                new CreateMerchantRequest(
                        "Test Merchant",
                        "https://test.example.com/webhook",
                        "^https://test\\.example\\.com/.*$",
                        "^https://test\\.example\\.com/cancel$",
                        MerchantMode.TEST,
                        null);
        ResponseEntity<MerchantResponse> response =
                restTemplate.postForEntity("/admin/merchants", request, MerchantResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }
}
