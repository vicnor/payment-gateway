package com.gateway.shared.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gateway.shared.web.config.SharedWebAutoConfiguration;
import com.gateway.shared.web.support.TestController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TestController.class)
@Import(SharedWebAutoConfiguration.class)
class GlobalExceptionHandlerTest {

    @Autowired MockMvc mockMvc;

    @Test
    void notFoundReturns404WithEnvelope() throws Exception {
        mockMvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value("not_found"))
                .andExpect(jsonPath("$.error.code").value("resource_not_found"))
                .andExpect(jsonPath("$.error.request_id").isNotEmpty());
    }

    @Test
    void validationExceptionReturns400WithParam() throws Exception {
        mockMvc.perform(get("/test/validation"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("validation_error"))
                .andExpect(jsonPath("$.error.param").value("amount"));
    }

    @Test
    void authExceptionReturns401() throws Exception {
        mockMvc.perform(get("/test/auth"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.type").value("authentication_error"));
    }

    @Test
    void permissionExceptionReturns403() throws Exception {
        mockMvc.perform(get("/test/permission"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.type").value("permission_error"));
    }

    @Test
    void idempotencyConflictReturns409() throws Exception {
        mockMvc.perform(get("/test/idempotency"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.type").value("idempotency_key_conflict"));
    }

    @Test
    void rateLimitReturns429() throws Exception {
        mockMvc.perform(get("/test/rate-limit"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.type").value("rate_limit_error"));
    }

    @Test
    void acquirerUnavailableReturns503() throws Exception {
        mockMvc.perform(get("/test/acquirer"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.type").value("acquirer_unavailable"));
    }

    @Test
    void unexpectedExceptionReturns500ApiError() throws Exception {
        mockMvc.perform(get("/test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.type").value("api_error"))
                .andExpect(jsonPath("$.error.code").value("internal_server_error"))
                .andExpect(jsonPath("$.error.message").value("Internal server error."));
    }

    @Test
    void requestIdFlowsIntoEnvelope() throws Exception {
        mockMvc.perform(get("/test/not-found"))
                .andExpect(jsonPath("$.error.request_id").isNotEmpty());

        // The request_id in the envelope must match the response header
        var result = mockMvc.perform(get("/test/not-found")).andReturn();
        String headerValue = result.getResponse().getHeader("X-Request-Id");
        String bodyRequestId =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(result.getResponse().getContentAsString())
                        .at("/error/request_id")
                        .asText();
        org.junit.jupiter.api.Assertions.assertEquals(headerValue, bodyRequestId);
    }

    @Test
    void validBodyBindingErrorContainsParam() throws Exception {
        mockMvc.perform(
                        post("/test/validate-body")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("validation_error"))
                .andExpect(jsonPath("$.error.param").value("name"));
    }

    @Test
    void notReadableBodyReturns400() throws Exception {
        mockMvc.perform(
                        post("/test/validate-body")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("not json at all"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("validation_error"))
                .andExpect(jsonPath("$.error.code").value("invalid_json"));
    }
}
