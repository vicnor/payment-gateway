package com.gateway.token.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gateway.token.config.CorsConfig;
import com.gateway.token.config.TokenProperties;
import com.gateway.token.config.TokenProperties.CorsProperties;
import com.gateway.token.config.TokenProperties.InternalProperties;
import com.gateway.token.config.TokenProperties.SessionSecretProperties;
import com.gateway.token.domain.TokenResult;
import com.gateway.token.domain.TokenizationService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TokenController.class)
@Import(TokenControllerTest.TestConfig.class)
class TokenControllerTest {

    /**
     * Provides config beans required by {@link CorsConfig} which is picked up as a
     * WebMvcConfigurer.
     */
    @TestConfiguration
    static class TestConfig {

        @Bean
        TokenProperties tokenProperties() {
            return new TokenProperties(
                    1800,
                    new CorsProperties(List.of("http://localhost:3000")),
                    new SessionSecretProperties(false),
                    new InternalProperties(List.of()));
        }
    }

    @Autowired MockMvc mockMvc;

    @MockitoBean TokenizationService tokenizationService;

    // --- happy path ---

    @Test
    void tokenizeReturns201WithSnakeCaseFields() throws Exception {
        when(tokenizationService.tokenize(eq("cs_test_session"), any()))
                .thenReturn(
                        new TokenResult(
                                "tok_01ABCDEFGHIJKLMNOPQRSTUVWX", "visa", "4242", 12, 2027));

        mockMvc.perform(
                        post("/checkout/cs_test_session/tokens")
                                .contentType(APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "card_number":  "4242424242424242",
                                          "exp_month":    12,
                                          "exp_year":     2027,
                                          "cvv":          "123",
                                          "holder_name":  "Test Person"
                                        }
                                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value("tok_01ABCDEFGHIJKLMNOPQRSTUVWX"))
                .andExpect(jsonPath("$.brand").value("visa"))
                .andExpect(jsonPath("$.last4").value("4242"))
                .andExpect(jsonPath("$.exp_month").value(12))
                .andExpect(jsonPath("$.exp_year").value(2027));
    }

    @Test
    void tokenizeResponseNeverContainsPanOrCvv() throws Exception {
        when(tokenizationService.tokenize(any(), any()))
                .thenReturn(
                        new TokenResult(
                                "tok_01ABCDEFGHIJKLMNOPQRSTUVWX", "visa", "4242", 12, 2027));

        mockMvc.perform(
                        post("/checkout/cs_test/tokens")
                                .contentType(APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "card_number": "4242424242424242",
                                          "exp_month":   12,
                                          "exp_year":    2027,
                                          "cvv":         "123",
                                          "holder_name": "Test Person"
                                        }
                                        """))
                .andExpect(status().isCreated())
                .andExpect(content().string(not(containsString("4242424242424242"))))
                .andExpect(content().string(not(containsString("\"cvv\""))))
                .andExpect(content().string(not(containsString("\"card_number\""))));
    }

    // --- request body validation ---

    @Test
    void missingCardNumberReturns400() throws Exception {
        mockMvc.perform(
                        post("/checkout/cs_test/tokens")
                                .contentType(APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "exp_month":   12,
                                          "exp_year":    2027,
                                          "cvv":         "123",
                                          "holder_name": "Test Person"
                                        }
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("validation_error"))
                .andExpect(jsonPath("$.error.param").value("cardNumber"));
    }

    @Test
    void nonDigitCardNumberReturns400() throws Exception {
        mockMvc.perform(
                        post("/checkout/cs_test/tokens")
                                .contentType(APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "card_number": "4242-4242-4242-4242",
                                          "exp_month":   12,
                                          "exp_year":    2027,
                                          "cvv":         "123",
                                          "holder_name": "Test Person"
                                        }
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("validation_error"));
    }

    @Test
    void invalidExpMonthReturns400() throws Exception {
        mockMvc.perform(
                        post("/checkout/cs_test/tokens")
                                .contentType(APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "card_number": "4242424242424242",
                                          "exp_month":   13,
                                          "exp_year":    2027,
                                          "cvv":         "123",
                                          "holder_name": "Test Person"
                                        }
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("validation_error"));
    }

    @Test
    void missingCvvReturns400() throws Exception {
        mockMvc.perform(
                        post("/checkout/cs_test/tokens")
                                .contentType(APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "card_number": "4242424242424242",
                                          "exp_month":   12,
                                          "exp_year":    2027,
                                          "holder_name": "Test Person"
                                        }
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("validation_error"));
    }

    @Test
    void missingHolderNameReturns400() throws Exception {
        mockMvc.perform(
                        post("/checkout/cs_test/tokens")
                                .contentType(APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "card_number": "4242424242424242",
                                          "exp_month":   12,
                                          "exp_year":    2027,
                                          "cvv":         "123"
                                        }
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("validation_error"));
    }

    @Test
    void emptyBodyReturns400WithInvalidJson() throws Exception {
        mockMvc.perform(post("/checkout/cs_test/tokens").contentType(APPLICATION_JSON).content(""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_json"));
    }

    @Test
    void requestIdHeaderIsPresent() throws Exception {
        when(tokenizationService.tokenize(any(), any()))
                .thenReturn(
                        new TokenResult(
                                "tok_01ABCDEFGHIJKLMNOPQRSTUVWX", "visa", "4242", 12, 2027));

        mockMvc.perform(
                        post("/checkout/cs_test/tokens")
                                .contentType(APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "card_number": "4242424242424242",
                                          "exp_month":   12,
                                          "exp_year":    2027,
                                          "cvv":         "123",
                                          "holder_name": "Test"
                                        }
                                        """))
                .andExpect(status().isCreated())
                .andExpect(header().exists("X-Request-Id"));
    }
}
