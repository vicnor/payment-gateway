package com.gateway.token.api.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.token.api.dto.TokenizeResponse;
import com.gateway.token.domain.TokenResult;
import org.junit.jupiter.api.Test;

class TokenizeMapperTest {

    @Test
    void toResponseMapsAllFieldsFromResult() {
        TokenResult result =
                new TokenResult("tok_01TESTTOKEN000000000000001", "visa", "4242", 12, 2027);

        TokenizeResponse response = TokenizeMapper.toResponse(result);

        assertThat(response.token()).isEqualTo("tok_01TESTTOKEN000000000000001");
        assertThat(response.brand()).isEqualTo("visa");
        assertThat(response.last4()).isEqualTo("4242");
        assertThat(response.expMonth()).isEqualTo(12);
        assertThat(response.expYear()).isEqualTo(2027);
    }

    @Test
    void toResponsePreservesExactTokenValue() {
        String tokenId = "tok_01HQX2YK3M4N5P6Q7R8S9T0V1W";
        TokenResult result = new TokenResult(tokenId, "mastercard", "5100", 6, 2030);

        assertThat(TokenizeMapper.toResponse(result).token()).isEqualTo(tokenId);
    }
}
