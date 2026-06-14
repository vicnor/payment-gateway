package com.gateway.shared.security;

import static com.gateway.shared.security.ApiKeyFormatTest.VALID_TEST_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateway.shared.security.cache.ApiKeyCandidateCache;
import com.gateway.shared.security.client.ApiKeyCandidate;
import com.gateway.shared.security.client.MerchantServiceClient;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@ExtendWith(MockitoExtension.class)
class ApiKeyAuthenticationFilterTest {

    private static PasswordEncoder encoder;
    private static String validKeyHash;
    private static String otherKeyHash;

    @BeforeAll
    static void computeHashes() {
        encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
        validKeyHash = encoder.encode(VALID_TEST_KEY);
        otherKeyHash =
                encoder.encode(
                        "sk_test_01JTESTBBBBBBBB000000000000_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB");
    }

    @Mock MerchantServiceClient merchantServiceClient;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ApiKeyCandidateCache cache =
                new ApiKeyCandidateCache(merchantServiceClient, Duration.ofMinutes(1));
        ApiKeyAuthenticationFilter filter =
                new ApiKeyAuthenticationFilter(
                        cache,
                        encoder,
                        new ObjectMapper(),
                        List.of("/actuator/**", "/internal/**"));

        mockMvc = MockMvcBuilders.standaloneSetup(new StubController()).addFilters(filter).build();
    }

    @Test
    void validKeyAuthenticatesAndExposesPrincipal() throws Exception {
        String prefix = ApiKeyFormat.extractPrefix(VALID_TEST_KEY);
        UUID keyId = UUID.randomUUID();
        ApiKeyCandidate candidate =
                new ApiKeyCandidate(keyId, "mer_test_123", prefix, validKeyHash, KeyMode.TEST);
        when(merchantServiceClient.lookupCandidates(prefix)).thenReturn(List.of(candidate));

        String body =
                mockMvc.perform(get("/v1/ping").header("Authorization", "Bearer " + VALID_TEST_KEY))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(body).contains("mer_test_123");
        assertThat(body).contains(keyId.toString());
    }

    @Test
    void invalidHashReturns401() throws Exception {
        String prefix = ApiKeyFormat.extractPrefix(VALID_TEST_KEY);
        ApiKeyCandidate candidate =
                new ApiKeyCandidate(
                        UUID.randomUUID(), "mer_test_123", prefix, otherKeyHash, KeyMode.TEST);
        when(merchantServiceClient.lookupCandidates(prefix)).thenReturn(List.of(candidate));

        mockMvc.perform(get("/v1/ping").header("Authorization", "Bearer " + VALID_TEST_KEY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.type").value("authentication_error"))
                .andExpect(jsonPath("$.error.code").value("invalid_api_key"));
    }

    @Test
    void revokedKeyReturns401WhenCandidatesEmpty() throws Exception {
        String prefix = ApiKeyFormat.extractPrefix(VALID_TEST_KEY);
        when(merchantServiceClient.lookupCandidates(prefix)).thenReturn(List.of());

        mockMvc.perform(get("/v1/ping").header("Authorization", "Bearer " + VALID_TEST_KEY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("invalid_api_key"));
    }

    @Test
    void missingAuthorizationHeaderReturns401() throws Exception {
        mockMvc.perform(get("/v1/ping"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("missing_authorization"));
    }

    @Test
    void malformedTokenReturns401() throws Exception {
        mockMvc.perform(get("/v1/ping").header("Authorization", "Bearer not-a-valid-key"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("invalid_api_key"));
    }

    @Test
    void actuatorPathBypassesAuth() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void internalPathBypassesAuth() throws Exception {
        mockMvc.perform(get("/internal/v1/something")).andExpect(status().isOk());
    }

    @Test
    void cachePreventsDuplicateClientCalls() throws Exception {
        String prefix = ApiKeyFormat.extractPrefix(VALID_TEST_KEY);
        UUID keyId = UUID.randomUUID();
        ApiKeyCandidate candidate =
                new ApiKeyCandidate(keyId, "mer_test_123", prefix, validKeyHash, KeyMode.TEST);
        when(merchantServiceClient.lookupCandidates(prefix)).thenReturn(List.of(candidate));

        mockMvc.perform(get("/v1/ping").header("Authorization", "Bearer " + VALID_TEST_KEY))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/ping").header("Authorization", "Bearer " + VALID_TEST_KEY))
                .andExpect(status().isOk());

        verify(merchantServiceClient, times(1)).lookupCandidates(prefix);
    }

    @RestController
    static class StubController {

        @GetMapping("/v1/ping")
        ResponseEntity<Map<String, String>> ping(HttpServletRequest request) {
            MerchantPrincipal principal = MerchantPrincipalHolder.current();
            if (principal == null) {
                return ResponseEntity.ok(Map.of("merchantId", "none"));
            }
            return ResponseEntity.ok(
                    Map.of(
                            "merchantId", principal.merchantId(),
                            "apiKeyId", principal.apiKeyId()));
        }

        @GetMapping("/actuator/health")
        ResponseEntity<Map<String, String>> health() {
            return ResponseEntity.ok(Map.of("status", "UP"));
        }

        @GetMapping("/internal/v1/something")
        ResponseEntity<Map<String, String>> internal() {
            return ResponseEntity.ok(Map.of("ok", "true"));
        }
    }
}
