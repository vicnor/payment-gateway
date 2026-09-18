package com.gateway.checkout.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.gateway.checkout.api.dto.CheckoutSessionResponse;
import com.gateway.checkout.api.dto.CreateCheckoutSessionRequest;
import com.gateway.checkout.domain.CheckoutSessionService;
import com.gateway.shared.security.KeyMode;
import com.gateway.shared.security.MerchantPrincipal;
import com.gateway.shared.web.exception.GlobalExceptionHandler;
import com.gateway.shared.web.idempotency.IdempotencyContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CheckoutSessionMerchantControllerTest {
    private static final MerchantPrincipal PRINCIPAL =
            new MerchantPrincipal("mer_test", "key_test", KeyMode.TEST);
    private static final IdempotencyContext IDEMPOTENCY =
            new IdempotencyContext("storage", "hash", "owner");
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        ObjectMapper mapper =
                new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new CheckoutSessionMerchantController(new FakeService()))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                        .build();
    }

    @Test
    void createReturnsDocumentedShape() throws Exception {
        mvc.perform(
                        post("/v1/checkout-sessions")
                                .requestAttr("merchant.principal", PRINCIPAL)
                                .requestAttr(IdempotencyContext.REQUEST_ATTRIBUTE, IDEMPOTENCY)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                            {"amount":19900,"currency":"DKK","merchant_reference":"order-1","return_url":"https://merchant.example/return","cancel_url":"https://merchant.example/cancel"}
                            """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("cs_01ARZ3NDEKTSV4RRFFQ69G5FAV"))
                .andExpect(jsonPath("$.object").value("checkout_session"))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.amount").value(19900))
                .andExpect(jsonPath("$.livemode").value(false));
    }

    @Test
    void malformedRequestReturnsValidationEnvelope() throws Exception {
        mvc.perform(
                        post("/v1/checkout-sessions")
                                .requestAttr("merchant.principal", PRINCIPAL)
                                .requestAttr(IdempotencyContext.REQUEST_ATTRIBUTE, IDEMPOTENCY)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("validation_error"));
    }

    @Test
    void retrieveDelegatesMerchantOwnership() throws Exception {
        mvc.perform(
                        get("/v1/checkout-sessions/cs_01ARZ3NDEKTSV4RRFFQ69G5FAV")
                                .requestAttr("merchant.principal", PRINCIPAL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchant_reference").value("order-1"));
    }

    private static final class FakeService extends CheckoutSessionService {
        private FakeService() {
            super(null, null, null, null, null, null, null);
        }

        @Override
        public CheckoutSessionResponse create(
                CreateCheckoutSessionRequest request,
                MerchantPrincipal principal,
                IdempotencyContext context) {
            return response();
        }

        @Override
        public CheckoutSessionResponse retrieve(String merchantId, String id) {
            return response();
        }

        @Override
        public CheckoutSessionResponse cancel(String merchantId, String id) {
            return response();
        }

        private static CheckoutSessionResponse response() {
            return new CheckoutSessionResponse(
                    "cs_01ARZ3NDEKTSV4RRFFQ69G5FAV",
                    "checkout_session",
                    "CREATED",
                    "https://checkout.test/checkout/cs_01?k=secret",
                    19900,
                    "DKK",
                    "order-1",
                    1800,
                    0,
                    false,
                    null);
        }
    }
}
