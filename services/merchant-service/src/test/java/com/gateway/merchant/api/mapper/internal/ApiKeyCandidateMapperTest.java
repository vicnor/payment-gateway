package com.gateway.merchant.api.mapper.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.gateway.merchant.api.dto.internal.ApiKeyCandidate;
import com.gateway.merchant.api.dto.internal.ApiKeyCandidatesResponse;
import com.gateway.merchant.domain.ApiKey;
import com.gateway.merchant.domain.MerchantMode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ApiKeyCandidateMapperTest {

    @Test
    void toCandidateMapsAllFields() {
        UUID id = UUID.fromString("018e1234-5678-7abc-def0-123456789012");
        ApiKey apiKey = Mockito.mock(ApiKey.class);
        when(apiKey.getId()).thenReturn(id);
        when(apiKey.getMerchantId()).thenReturn("mer_01TEST");
        when(apiKey.getKeyPrefix()).thenReturn("sk_test_01ABC1");
        when(apiKey.getKeyHash()).thenReturn("$argon2id$somehash");
        when(apiKey.getMode()).thenReturn(MerchantMode.TEST);

        ApiKeyCandidate candidate = ApiKeyCandidateMapper.toCandidate(apiKey);

        assertThat(candidate.id()).isEqualTo(id);
        assertThat(candidate.merchantId()).isEqualTo("mer_01TEST");
        assertThat(candidate.keyPrefix()).isEqualTo("sk_test_01ABC1");
        assertThat(candidate.keyHash()).isEqualTo("$argon2id$somehash");
        assertThat(candidate.mode()).isEqualTo(MerchantMode.TEST);
    }

    @Test
    void toResponseWrapsEmptyList() {
        ApiKeyCandidatesResponse response = ApiKeyCandidateMapper.toResponse(List.of());

        assertThat(response.keys()).isEmpty();
    }

    @Test
    void toResponseMapsMultipleCandidates() {
        ApiKey first = Mockito.mock(ApiKey.class);
        when(first.getId()).thenReturn(UUID.randomUUID());
        when(first.getMerchantId()).thenReturn("mer_01A");
        when(first.getKeyPrefix()).thenReturn("sk_test_prefix1");
        when(first.getKeyHash()).thenReturn("hash1");
        when(first.getMode()).thenReturn(MerchantMode.TEST);

        ApiKey second = Mockito.mock(ApiKey.class);
        when(second.getId()).thenReturn(UUID.randomUUID());
        when(second.getMerchantId()).thenReturn("mer_01B");
        when(second.getKeyPrefix()).thenReturn("sk_test_prefix1");
        when(second.getKeyHash()).thenReturn("hash2");
        when(second.getMode()).thenReturn(MerchantMode.LIVE);

        ApiKeyCandidatesResponse response =
                ApiKeyCandidateMapper.toResponse(List.of(first, second));

        assertThat(response.keys()).hasSize(2);
        assertThat(response.keys().get(0).merchantId()).isEqualTo("mer_01A");
        assertThat(response.keys().get(1).merchantId()).isEqualTo("mer_01B");
    }
}
