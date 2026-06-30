package com.gateway.merchant.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.merchant.MerchantServiceApplication;
import com.gateway.merchant.domain.ApiKey;
import com.gateway.merchant.domain.Merchant;
import com.gateway.merchant.domain.MerchantMode;
import com.gateway.merchant.persistence.ApiKeyRepository;
import com.gateway.merchant.persistence.MerchantRepository;
import com.gateway.shared.testing.AbstractPostgresIT;
import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.http.ResponseEntity;

@SpringBootTest(
        classes = MerchantServiceApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT)
class MerchantInternalIT extends AbstractPostgresIT {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private ApiKeyRepository apiKeyRepository;
    @Autowired private CacheManager cacheManager;

    private static final String MERCHANT_ID = "mer_01ITMERCHANT000000000000000";
    private static final String KEY_PREFIX = "sk_test_it00000";

    @BeforeEach
    void setUp() {
        apiKeyRepository.deleteAll();
        merchantRepository.deleteAll();
        cacheManager.getCache("internalMerchantsById").clear();
        cacheManager.getCache("internalApiKeysByPrefix").clear();
    }

    @Test
    void getMerchantReturnsFullConfig() {
        Merchant merchant =
                new Merchant(
                        MERCHANT_ID,
                        "IT Merchant",
                        "https://example.com/webhook",
                        "^https://example\\.com/.*$",
                        "^https://example\\.com/cancel$",
                        null,
                        MerchantMode.TEST);
        merchantRepository.save(merchant);

        ResponseEntity<String> response =
                restTemplate.getForEntity("/internal/v1/merchants/" + MERCHANT_ID, String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"id\":\"" + MERCHANT_ID + "\"");
        assertThat(response.getBody()).contains("\"name\":\"IT Merchant\"");
        assertThat(response.getBody()).contains("\"callback_url\":\"https://example.com/webhook\"");
        assertThat(response.getBody()).contains("\"mode\":\"TEST\"");
        assertThat(response.getBody()).contains("\"status\":\"ACTIVE\"");
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isNotNull();
    }

    @Test
    void getMerchantReturns404ForUnknownId() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        "/internal/v1/merchants/mer_does_not_exist", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).contains("\"type\":\"not_found\"");
        assertThat(response.getBody()).contains("\"code\":\"resource_not_found\"");
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isNotNull();
    }

    @Test
    void getApiKeyCandidatesReturnsCandidatesForKnownPrefix() {
        Merchant merchant =
                new Merchant(
                        MERCHANT_ID,
                        "IT Merchant",
                        "https://example.com/webhook",
                        "^https://example\\.com/.*$",
                        "^https://example\\.com/cancel$",
                        null,
                        MerchantMode.TEST);
        merchantRepository.save(merchant);

        ApiKey apiKey =
                new ApiKey(
                        MERCHANT_ID, KEY_PREFIX, "$argon2id$fakehash", MerchantMode.TEST, "IT key");
        apiKeyRepository.save(apiKey);

        ResponseEntity<String> response =
                restTemplate.getForEntity("/internal/v1/api-keys/" + KEY_PREFIX, String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"keys\"");
        assertThat(response.getBody()).contains("\"merchant_id\":\"" + MERCHANT_ID + "\"");
        assertThat(response.getBody()).contains("\"key_prefix\":\"" + KEY_PREFIX + "\"");
        assertThat(response.getBody()).contains("\"key_hash\":\"$argon2id$fakehash\"");
        assertThat(response.getBody()).contains("\"mode\":\"TEST\"");
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isNotNull();
    }

    @Test
    void getApiKeyCandidatesReturnsEmptyListForUnknownPrefix() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/internal/v1/api-keys/sk_test_xxxxxxx", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"keys\":[]");
    }

    @Test
    void merchantCacheHitsOnSecondRequest() {
        Merchant merchant =
                new Merchant(
                        MERCHANT_ID,
                        "IT Merchant",
                        "https://example.com/webhook",
                        "^https://example\\.com/.*$",
                        "^https://example\\.com/cancel$",
                        null,
                        MerchantMode.TEST);
        merchantRepository.save(merchant);

        Cache<Object, Object> nativeCache = getNativeCache("internalMerchantsById");
        long hitsBefore = nativeCache.stats().hitCount();

        restTemplate.getForEntity("/internal/v1/merchants/" + MERCHANT_ID, String.class);
        restTemplate.getForEntity("/internal/v1/merchants/" + MERCHANT_ID, String.class);

        assertThat(nativeCache.stats().hitCount()).isEqualTo(hitsBefore + 1);
    }

    @Test
    void apiKeysCacheHitsOnSecondRequest() {
        Merchant merchant =
                new Merchant(
                        MERCHANT_ID,
                        "IT Merchant",
                        "https://example.com/webhook",
                        "^https://example\\.com/.*$",
                        "^https://example\\.com/cancel$",
                        null,
                        MerchantMode.TEST);
        merchantRepository.save(merchant);
        ApiKey apiKey =
                new ApiKey(
                        MERCHANT_ID, KEY_PREFIX, "$argon2id$fakehash", MerchantMode.TEST, "IT key");
        apiKeyRepository.save(apiKey);

        Cache<Object, Object> nativeCache = getNativeCache("internalApiKeysByPrefix");
        long hitsBefore = nativeCache.stats().hitCount();

        restTemplate.getForEntity("/internal/v1/api-keys/" + KEY_PREFIX, String.class);
        restTemplate.getForEntity("/internal/v1/api-keys/" + KEY_PREFIX, String.class);

        assertThat(nativeCache.stats().hitCount()).isEqualTo(hitsBefore + 1);
    }

    @SuppressWarnings("unchecked")
    private Cache<Object, Object> getNativeCache(String cacheName) {
        CaffeineCache caffeineCache = (CaffeineCache) cacheManager.getCache(cacheName);
        return (Cache<Object, Object>) caffeineCache.getNativeCache();
    }
}
