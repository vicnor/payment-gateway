package com.gateway.checkout.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gateway.checkout.domain.CheckoutSessionAuthenticator;
import com.gateway.checkout.persistence.CheckoutSessionItem;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CheckoutSessionInternalControllerTest {
    @Test
    void returnsOnlyAuthenticatedSessionContext() throws Exception {
        CheckoutSessionAuthenticator authenticator = mock(CheckoutSessionAuthenticator.class);
        CheckoutSessionItem item = new CheckoutSessionItem();
        item.setSessionId("cs_01ARZ3NDEKTSV4RRFFQ69G5FAV");
        item.setMerchantId("mer_test");
        item.setExpiresAt(2000L);
        when(authenticator.authenticateDigest(item.getSessionId(), "digest")).thenReturn(item);
        MockMvc mvc =
                MockMvcBuilders.standaloneSetup(
                                new CheckoutSessionInternalController(authenticator))
                        .build();
        mvc.perform(
                        get("/internal/v1/checkout-sessions/{id}/authenticate", item.getSessionId())
                                .header("X-Checkout-Session-Secret-SHA256", "digest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchantId").value("mer_test"))
                .andExpect(jsonPath("$.sessionSecretHash").doesNotExist());
    }
}
