package com.gateway.merchant.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gateway.merchant.domain.ApiKey;
import com.gateway.merchant.domain.Merchant;
import com.gateway.merchant.domain.MerchantInternalService;
import com.gateway.merchant.domain.MerchantMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MerchantInternalController.class)
class MerchantInternalControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean MerchantInternalService merchantInternalService;

    @Test
    void getApiKeyCandidatesReturnsCandidateList() throws Exception {
        ApiKey mockKey = Mockito.mock(ApiKey.class);
        when(mockKey.getId()).thenReturn(UUID.fromString("018e1234-5678-7abc-def0-123456789012"));
        when(mockKey.getMerchantId()).thenReturn("mer_01TEST");
        when(mockKey.getKeyPrefix()).thenReturn("sk_test_01ABC1");
        when(mockKey.getKeyHash()).thenReturn("$argon2id$v=19$m=16384,t=2,p=1$somehash");
        when(mockKey.getMode()).thenReturn(MerchantMode.TEST);
        when(merchantInternalService.findCandidatesByPrefix("sk_test_01ABC1"))
                .thenReturn(List.of(mockKey));

        mockMvc.perform(get("/internal/v1/api-keys/sk_test_01ABC1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys").isArray())
                .andExpect(jsonPath("$.keys.length()").value(1))
                .andExpect(jsonPath("$.keys[0].id").value("018e1234-5678-7abc-def0-123456789012"))
                .andExpect(jsonPath("$.keys[0].merchantId").value("mer_01TEST"))
                .andExpect(jsonPath("$.keys[0].keyPrefix").value("sk_test_01ABC1"))
                .andExpect(
                        jsonPath("$.keys[0].keyHash")
                                .value("$argon2id$v=19$m=16384,t=2,p=1$somehash"))
                .andExpect(jsonPath("$.keys[0].mode").value("TEST"));
    }

    @Test
    void getApiKeyCandidatesReturnsEmptyListWhenNoneFound() throws Exception {
        when(merchantInternalService.findCandidatesByPrefix("sk_test_notexist"))
                .thenReturn(List.of());

        mockMvc.perform(get("/internal/v1/api-keys/sk_test_notexist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys").isArray())
                .andExpect(jsonPath("$.keys.length()").value(0));
    }

    @Test
    void getMerchantReturnsMerchantResponse() throws Exception {
        Merchant merchant =
                new Merchant(
                        "mer_01TEST",
                        "Acme Corp",
                        "https://acme.example.com/webhook",
                        "^https://acme\\.example\\.com/.*$",
                        "^https://acme\\.example\\.com/cancel$",
                        null,
                        MerchantMode.TEST);
        when(merchantInternalService.findMerchant("mer_01TEST")).thenReturn(Optional.of(merchant));

        mockMvc.perform(get("/internal/v1/merchants/mer_01TEST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("mer_01TEST"))
                .andExpect(jsonPath("$.name").value("Acme Corp"))
                .andExpect(jsonPath("$.callbackUrl").value("https://acme.example.com/webhook"))
                .andExpect(jsonPath("$.mode").value("TEST"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void getMerchantReturns404WhenNotFound() throws Exception {
        when(merchantInternalService.findMerchant("mer_does_not_exist"))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/internal/v1/merchants/mer_does_not_exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value("not_found"))
                .andExpect(jsonPath("$.error.code").value("resource_not_found"));
    }
}
