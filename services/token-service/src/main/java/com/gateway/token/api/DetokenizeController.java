package com.gateway.token.api;

import com.gateway.shared.security.InternalCallerAuthenticationFilter;
import com.gateway.token.api.dto.DetokenizeResponse;
import com.gateway.token.api.mapper.DetokenizeMapper;
import com.gateway.token.domain.DetokenizationService;
import com.gateway.token.domain.DetokenizeResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Internal detokenize endpoint — callable only by allowlisted services (payment-service in v1). */
@RestController
public class DetokenizeController {

    private final DetokenizationService detokenizationService;

    public DetokenizeController(DetokenizationService detokenizationService) {
        this.detokenizationService = detokenizationService;
    }

    @PostMapping("/internal/v1/tokens/{token}/detokenize")
    public DetokenizeResponse detokenize(@PathVariable String token, HttpServletRequest request) {
        String callerId =
                (String) request.getAttribute(InternalCallerAuthenticationFilter.CALLER_ID_ATTR);
        DetokenizeResult result = detokenizationService.detokenize(token, callerId);
        return DetokenizeMapper.toResponse(result);
    }
}
