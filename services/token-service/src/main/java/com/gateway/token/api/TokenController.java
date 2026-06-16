package com.gateway.token.api;

import com.gateway.token.api.dto.TokenizeRequest;
import com.gateway.token.api.dto.TokenizeResponse;
import com.gateway.token.api.mapper.TokenizeMapper;
import com.gateway.token.domain.TokenResult;
import com.gateway.token.domain.TokenizationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Browser-facing card tokenization endpoint.
 *
 * <p>Accepts raw card data from the Next.js checkout page, validates it, envelope-encrypts it via
 * KMS + AES-256-GCM, and returns an opaque single-use token with safe card metadata.
 */
@RestController
public class TokenController {

    private final TokenizationService tokenizationService;

    public TokenController(TokenizationService tokenizationService) {
        this.tokenizationService = tokenizationService;
    }

    @PostMapping("/checkout/{sessionId}/tokens")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenizeResponse tokenize(
            @PathVariable String sessionId, @Valid @RequestBody TokenizeRequest request) {
        TokenResult result = tokenizationService.tokenize(sessionId, request);
        return TokenizeMapper.toResponse(result);
    }
}
