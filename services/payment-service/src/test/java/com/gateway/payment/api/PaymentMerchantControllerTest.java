package com.gateway.payment.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gateway.payment.api.dto.PaymentListResponse;
import com.gateway.payment.api.dto.PaymentResponse;
import com.gateway.payment.api.dto.PaymentResponse.PaymentMethodDetailsResponse;
import com.gateway.payment.domain.PaymentQueryService;
import com.gateway.shared.security.KeyMode;
import com.gateway.shared.security.MerchantPrincipal;
import com.gateway.shared.web.error.NotFoundException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        controllers = PaymentMerchantController.class,
        properties = "shared.security.merchant-service.base-url=http://localhost:19999")
@AutoConfigureMockMvc(addFilters = false)
class PaymentMerchantControllerTest {

    private static final String MERCHANT_ID = "mer_test_merchant";
    private static final String PAYMENT_ID = "pay_01ARZ3NDEKTSV4RRFFQ69G5FAV";
    private static final MerchantPrincipal PRINCIPAL =
            new MerchantPrincipal(MERCHANT_ID, "key-id", KeyMode.TEST);

    @Autowired MockMvc mockMvc;

    @MockitoBean PaymentQueryService paymentQueryService;

    @Test
    void retrieveReturnsDocumentedPaymentShape() throws Exception {
        when(paymentQueryService.retrieve(MERCHANT_ID, PAYMENT_ID)).thenReturn(payment());

        mockMvc.perform(
                        get("/v1/payments/{id}", PAYMENT_ID)
                                .requestAttr("merchant.principal", PRINCIPAL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(PAYMENT_ID))
                .andExpect(jsonPath("$.object").value("payment"))
                .andExpect(jsonPath("$.checkout_session_id").value("cs_test_session"))
                .andExpect(jsonPath("$.amount").value(19900))
                .andExpect(jsonPath("$.amount_captured").value(19900))
                .andExpect(jsonPath("$.payment_method_details.brand").value("visa"))
                .andExpect(jsonPath("$.payment_method_details.last4").value("4242"))
                .andExpect(jsonPath("$.merchant_reference").value("order-001"))
                .andExpect(jsonPath("$.livemode").value(false));
    }

    @Test
    void listReturnsListEnvelope() throws Exception {
        when(paymentQueryService.list(MERCHANT_ID, "10", null, "100", "200"))
                .thenReturn(
                        new PaymentListResponse("list", List.of(payment()), true, "/v1/payments"));

        mockMvc.perform(
                        get("/v1/payments")
                                .param("limit", "10")
                                .param("created.gte", "100")
                                .param("created.lte", "200")
                                .requestAttr("merchant.principal", PRINCIPAL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("list"))
                .andExpect(jsonPath("$.data[0].id").value(PAYMENT_ID))
                .andExpect(jsonPath("$.has_more").value(true))
                .andExpect(jsonPath("$.url").value("/v1/payments"));
    }

    @Test
    void missingOrForeignPaymentReturns404Envelope() throws Exception {
        when(paymentQueryService.retrieve(MERCHANT_ID, "not-a-payment"))
                .thenThrow(new NotFoundException("Payment", "not-a-payment"));

        mockMvc.perform(
                        get("/v1/payments/not-a-payment")
                                .requestAttr("merchant.principal", PRINCIPAL))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value("not_found"))
                .andExpect(jsonPath("$.error.code").value("resource_not_found"));
    }

    private static PaymentResponse payment() {
        return new PaymentResponse(
                PAYMENT_ID,
                "payment",
                "cs_test_session",
                19900L,
                19900L,
                0L,
                "DKK",
                "CAPTURED",
                "card",
                new PaymentMethodDetailsResponse("visa", "4242", 12, 2027, "DK"),
                "order-001",
                null,
                null,
                1748160030L,
                1748160031L,
                1748160031L,
                Map.of("cart_id", "abc123"),
                false);
    }
}
