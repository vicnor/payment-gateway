package com.gateway.merchant.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gateway.merchant.domain.ApiKey;
import com.gateway.merchant.domain.ApiKeyService;
import com.gateway.merchant.domain.IssuedApiKey;
import com.gateway.merchant.domain.Merchant;
import com.gateway.merchant.domain.MerchantMode;
import com.gateway.merchant.domain.MerchantService;
import com.gateway.merchant.domain.RotatedSecret;
import com.gateway.merchant.domain.WebhookSecret;
import com.gateway.merchant.domain.WebhookSecretService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MerchantAdminController.class)
class MerchantAdminControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean MerchantService merchantService;
    @MockitoBean ApiKeyService apiKeyService;
    @MockitoBean WebhookSecretService webhookSecretService;

    @Test
    void createMerchantWithBlankNameReturns400() throws Exception {
        String body =
                """
                {
                  "name": "  ",
                  "callbackUrl": "https://acme.example.com/webhook",
                  "returnUrlPattern": "^https://acme\\\\.example\\\\.com/.*$",
                  "cancelUrlPattern": "^https://acme\\\\.example\\\\.com/cancel$",
                  "mode": "TEST"
                }
                """;

        mockMvc.perform(post("/admin/merchants").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("validation_error"))
                .andExpect(jsonPath("$.error.param").value("name"));
    }

    @Test
    void createMerchantWithHttpCallbackUrlOnLiveModeReturns400() throws Exception {
        String body =
                """
                {
                  "name": "Acme Corp",
                  "callbackUrl": "http://acme.example.com/webhook",
                  "returnUrlPattern": "^https://acme\\\\.example\\\\.com/.*$",
                  "cancelUrlPattern": "^https://acme\\\\.example\\\\.com/cancel$",
                  "mode": "LIVE"
                }
                """;

        mockMvc.perform(post("/admin/merchants").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("validation_error"))
                .andExpect(jsonPath("$.error.param").value("callbackUrl"));
    }

    @Test
    void createMerchantWithInvalidReturnUrlPatternReturns400() throws Exception {
        String body =
                """
                {
                  "name": "Acme Corp",
                  "callbackUrl": "https://acme.example.com/webhook",
                  "returnUrlPattern": "^https://(unclosed",
                  "cancelUrlPattern": "^https://acme\\\\.example\\\\.com/cancel$",
                  "mode": "TEST"
                }
                """;

        mockMvc.perform(post("/admin/merchants").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("validation_error"))
                .andExpect(jsonPath("$.error.param").value("returnUrlPattern"));
    }

    @Test
    void createMerchantWithInvalidModeReturns400() throws Exception {
        String body =
                """
                {
                  "name": "Acme Corp",
                  "callbackUrl": "https://acme.example.com/webhook",
                  "returnUrlPattern": "^https://.*$",
                  "cancelUrlPattern": "^https://.*$",
                  "mode": "INVALID_MODE"
                }
                """;

        mockMvc.perform(post("/admin/merchants").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("validation_error"))
                .andExpect(jsonPath("$.error.code").value("invalid_json"));
    }

    @Test
    void issueApiKeyDelegatesToServiceAndReturnsKey() throws Exception {
        ApiKey mockApiKey = Mockito.mock(ApiKey.class);
        when(mockApiKey.getId())
                .thenReturn(UUID.fromString("018e1234-5678-7abc-def0-123456789012"));
        when(mockApiKey.getKeyPrefix()).thenReturn("sk_test_01ABC1");
        when(mockApiKey.getMode()).thenReturn(MerchantMode.TEST);
        when(mockApiKey.getCreatedAt()).thenReturn(Instant.parse("2024-01-01T00:00:00Z"));

        String plainKey = "sk_test_01ABC123456789012345678_abcdefgh12345678901234567890abcde=";
        IssuedApiKey issued = new IssuedApiKey(plainKey, mockApiKey);
        when(apiKeyService.issue(eq("mer_01ABC"), any())).thenReturn(issued);

        mockMvc.perform(
                        post("/admin/merchants/mer_01ABC/api-keys")
                                .contentType(APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").value(plainKey))
                .andExpect(jsonPath("$.keyPrefix").value("sk_test_01ABC1"))
                .andExpect(jsonPath("$.mode").value("TEST"));
    }

    @Test
    void rotateWebhookSecretDelegatesToServiceAndReturnsSecret() throws Exception {
        WebhookSecret mockSecret = Mockito.mock(WebhookSecret.class);
        when(mockSecret.getId())
                .thenReturn(UUID.fromString("018e1234-5678-7abc-def0-123456789012"));
        when(mockSecret.getCreatedAt()).thenReturn(Instant.parse("2024-01-01T00:00:00Z"));

        String plainSecret = "whsec_abc123def456";
        RotatedSecret rotated = new RotatedSecret(plainSecret, mockSecret);
        when(webhookSecretService.rotate("mer_01ABC")).thenReturn(rotated);

        mockMvc.perform(post("/admin/merchants/mer_01ABC/webhook-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secret").value(plainSecret));
    }

    private Merchant stubMerchant() {
        return new Merchant(
                "mer_01TESTMERCHANT00000000000",
                "Acme Corp",
                "https://acme.example.com/webhook",
                "^https://acme\\.example\\.com/.*$",
                "^https://acme\\.example\\.com/cancel$",
                null,
                MerchantMode.TEST);
    }
}
