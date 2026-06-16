package com.gateway.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class InternalCallerAuthenticationFilterTest {

    private static final String CALLER_ID = "payment-service";
    private static final String SECRET = "super-secret-123";

    private MockMvc mockMvc;
    private CapturingController capturingController;

    @BeforeEach
    void setUp() {
        capturingController = new CapturingController();
        InternalCallerAuthenticationFilter filter =
                new InternalCallerAuthenticationFilter(
                        List.of(new CallerConfig(CALLER_ID, SECRET)), new ObjectMapper());
        mockMvc = MockMvcBuilders.standaloneSetup(capturingController).addFilter(filter).build();
    }

    @Test
    void validCallerProceeds() throws Exception {
        mockMvc.perform(
                        get("/internal/v1/tokens/tok_abc/detokenize")
                                .header(
                                        InternalCallerAuthenticationFilter.HEADER_CALLER_SERVICE,
                                        CALLER_ID)
                                .header(
                                        InternalCallerAuthenticationFilter.HEADER_INTERNAL_TOKEN,
                                        SECRET))
                .andExpect(status().isOk());

        assertThat(capturingController.capturedCallerId).isEqualTo(CALLER_ID);
    }

    @Test
    void missingCallerServiceHeaderReturnsForbidden() throws Exception {
        mockMvc.perform(
                        get("/internal/v1/anything")
                                .header(
                                        InternalCallerAuthenticationFilter.HEADER_INTERNAL_TOKEN,
                                        SECRET))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.type").value("permission_error"));
    }

    @Test
    void missingInternalTokenHeaderReturnsForbidden() throws Exception {
        mockMvc.perform(
                        get("/internal/v1/anything")
                                .header(
                                        InternalCallerAuthenticationFilter.HEADER_CALLER_SERVICE,
                                        CALLER_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.type").value("permission_error"));
    }

    @Test
    void unknownCallerReturnsForbidden() throws Exception {
        mockMvc.perform(
                        get("/internal/v1/anything")
                                .header(
                                        InternalCallerAuthenticationFilter.HEADER_CALLER_SERVICE,
                                        "unknown-service")
                                .header(
                                        InternalCallerAuthenticationFilter.HEADER_INTERNAL_TOKEN,
                                        SECRET))
                .andExpect(status().isForbidden());
    }

    @Test
    void wrongSecretReturnsForbidden() throws Exception {
        mockMvc.perform(
                        get("/internal/v1/anything")
                                .header(
                                        InternalCallerAuthenticationFilter.HEADER_CALLER_SERVICE,
                                        CALLER_ID)
                                .header(
                                        InternalCallerAuthenticationFilter.HEADER_INTERNAL_TOKEN,
                                        "wrong-secret"))
                .andExpect(status().isForbidden());
    }

    @Test
    void nonInternalPathIsSkipped() throws Exception {
        mockMvc.perform(get("/checkout/cs_123/tokens")).andExpect(status().isOk());

        assertThat(capturingController.capturedCallerId).isNull();
    }

    @RestController
    static class CapturingController {
        String capturedCallerId;

        @GetMapping("/internal/v1/tokens/{token}/detokenize")
        ResponseEntity<Void> detokenize(HttpServletRequest request) {
            capturedCallerId =
                    (String)
                            request.getAttribute(InternalCallerAuthenticationFilter.CALLER_ID_ATTR);
            return ResponseEntity.ok().build();
        }

        @GetMapping("/internal/v1/anything")
        ResponseEntity<Void> anything() {
            return ResponseEntity.ok().build();
        }

        @GetMapping("/checkout/cs_123/tokens")
        ResponseEntity<Void> publicEndpoint() {
            return ResponseEntity.ok().build();
        }
    }
}
