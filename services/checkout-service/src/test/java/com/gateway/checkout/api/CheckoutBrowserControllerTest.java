package com.gateway.checkout.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.gateway.checkout.api.dto.CompleteCheckoutResponse;
import com.gateway.checkout.api.dto.SessionDetailsResponse;
import com.gateway.checkout.domain.BrowserCheckoutService;
import com.gateway.shared.web.exception.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CheckoutBrowserControllerTest {
    private static final String ID = "cs_01ARZ3NDEKTSV4RRFFQ69G5FAV";
    private BrowserCheckoutService service;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        service = mock(BrowserCheckoutService.class);
        ObjectMapper mapper =
                new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mvc =
                MockMvcBuilders.standaloneSetup(new CheckoutBrowserController(service))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                        .build();
    }

    @Test
    void returnsBrowserSafeSessionDetailsWithoutCaching() throws Exception {
        when(service.details(ID, "secret"))
                .thenReturn(
                        new SessionDetailsResponse(
                                ID,
                                "IN_PROGRESS",
                                19900,
                                "DKK",
                                "Order #1",
                                new SessionDetailsResponse.Merchant(
                                        "Acme", "https://example/logo.png", "#123456"),
                                List.of("card"),
                                "da-DK",
                                2000));
        mvc.perform(
                        get("/checkout/{id}/session-details", ID)
                                .header("X-Checkout-Session-Secret", "secret"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.merchant.name").value("Acme"))
                .andExpect(jsonPath("$.available_payment_methods[0]").value("card"));
    }

    @Test
    void completesWithDocumentedShape() throws Exception {
        String token = "tok_01ARZ3NDEKTSV4RRFFQ69G5FAV";
        when(service.complete(ID, "secret", token))
                .thenReturn(
                        new CompleteCheckoutResponse(
                                "COMPLETED",
                                "pay_01",
                                null,
                                null,
                                null,
                                "https://merchant.example/return"));
        mvc.perform(
                        post("/checkout/{id}/complete", ID)
                                .header("X-Checkout-Session-Secret", "secret")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.payment_id").value("pay_01"));
    }
}
