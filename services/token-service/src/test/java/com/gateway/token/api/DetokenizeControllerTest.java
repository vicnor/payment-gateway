package com.gateway.token.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gateway.shared.security.CallerConfig;
import com.gateway.shared.web.error.ConflictException;
import com.gateway.shared.web.error.NotFoundException;
import com.gateway.token.config.InternalCallerConfig;
import com.gateway.token.config.TokenProperties;
import com.gateway.token.config.TokenProperties.CorsProperties;
import com.gateway.token.config.TokenProperties.InternalProperties;
import com.gateway.token.config.TokenProperties.SessionSecretProperties;
import com.gateway.token.domain.DetokenizationService;
import com.gateway.token.domain.DetokenizeResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DetokenizeController.class)
@Import({DetokenizeControllerTest.TestConfig.class, InternalCallerConfig.class})
class DetokenizeControllerTest {

    private static final String CALLER_ID = "payment-service";
    private static final String SECRET = "test-secret";
    private static final String TOKEN_ID = "tok_01ABCDEFGHIJKLMNOPQRSTUVWX";

    @TestConfiguration
    static class TestConfig {

        @Bean
        TokenProperties tokenProperties() {
            return new TokenProperties(
                    1800,
                    new CorsProperties(List.of("http://localhost:3000")),
                    new SessionSecretProperties(false),
                    new InternalProperties(List.of(new CallerConfig(CALLER_ID, SECRET))));
        }
    }

    @Autowired MockMvc mockMvc;

    @MockitoBean DetokenizationService detokenizationService;

    @Test
    void detokenizeReturns200WithCardData() throws Exception {
        when(detokenizationService.detokenize(eq(TOKEN_ID), eq(CALLER_ID)))
                .thenReturn(new DetokenizeResult("4242424242424242", 12, 2027));

        mockMvc.perform(
                        post("/internal/v1/tokens/{token}/detokenize", TOKEN_ID)
                                .header("X-Caller-Service", CALLER_ID)
                                .header("X-Internal-Token", SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pan").value("4242424242424242"))
                .andExpect(jsonPath("$.exp_month").value(12))
                .andExpect(jsonPath("$.exp_year").value(2027));
    }

    @Test
    void missingCallerServiceHeaderReturnsForbidden() throws Exception {
        mockMvc.perform(
                        post("/internal/v1/tokens/{token}/detokenize", TOKEN_ID)
                                .header("X-Internal-Token", SECRET))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.type").value("permission_error"));
    }

    @Test
    void missingInternalTokenHeaderReturnsForbidden() throws Exception {
        mockMvc.perform(
                        post("/internal/v1/tokens/{token}/detokenize", TOKEN_ID)
                                .header("X-Caller-Service", CALLER_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.type").value("permission_error"));
    }

    @Test
    void wrongSecretReturnsForbidden() throws Exception {
        mockMvc.perform(
                        post("/internal/v1/tokens/{token}/detokenize", TOKEN_ID)
                                .header("X-Caller-Service", CALLER_ID)
                                .header("X-Internal-Token", "wrong-secret"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownCallerReturnsForbidden() throws Exception {
        mockMvc.perform(
                        post("/internal/v1/tokens/{token}/detokenize", TOKEN_ID)
                                .header("X-Caller-Service", "unknown-service")
                                .header("X-Internal-Token", SECRET))
                .andExpect(status().isForbidden());
    }

    @Test
    void tokenNotFoundReturns404() throws Exception {
        when(detokenizationService.detokenize(any(), any()))
                .thenThrow(new NotFoundException("Token", TOKEN_ID));

        mockMvc.perform(
                        post("/internal/v1/tokens/{token}/detokenize", TOKEN_ID)
                                .header("X-Caller-Service", CALLER_ID)
                                .header("X-Internal-Token", SECRET))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.type").value("not_found"));
    }

    @Test
    void tokenAlreadyUsedReturns409() throws Exception {
        when(detokenizationService.detokenize(any(), any()))
                .thenThrow(
                        new ConflictException(
                                "token_already_used", "Token has already been used."));

        mockMvc.perform(
                        post("/internal/v1/tokens/{token}/detokenize", TOKEN_ID)
                                .header("X-Caller-Service", CALLER_ID)
                                .header("X-Internal-Token", SECRET))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("token_already_used"));
    }
}
