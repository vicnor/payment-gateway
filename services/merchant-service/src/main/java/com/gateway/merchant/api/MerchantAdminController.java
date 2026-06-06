package com.gateway.merchant.api;

import com.gateway.merchant.api.dto.CreateMerchantRequest;
import com.gateway.merchant.api.dto.IssueApiKeyRequest;
import com.gateway.merchant.api.dto.IssueApiKeyResponse;
import com.gateway.merchant.api.dto.MerchantResponse;
import com.gateway.merchant.api.dto.RotateWebhookSecretResponse;
import com.gateway.merchant.api.mapper.ApiKeyMapper;
import com.gateway.merchant.api.mapper.MerchantMapper;
import com.gateway.merchant.api.mapper.WebhookSecretMapper;
import com.gateway.merchant.domain.ApiKeyService;
import com.gateway.merchant.domain.Merchant;
import com.gateway.merchant.domain.MerchantService;
import com.gateway.merchant.domain.WebhookSecretService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin")
public class MerchantAdminController {

    private final MerchantService merchantService;
    private final ApiKeyService apiKeyService;
    private final WebhookSecretService webhookSecretService;

    public MerchantAdminController(
            MerchantService merchantService,
            ApiKeyService apiKeyService,
            WebhookSecretService webhookSecretService) {
        this.merchantService = merchantService;
        this.apiKeyService = apiKeyService;
        this.webhookSecretService = webhookSecretService;
    }

    @PostMapping("/merchants")
    @ResponseStatus(HttpStatus.CREATED)
    public MerchantResponse createMerchant(@Valid @RequestBody CreateMerchantRequest request) {
        Merchant merchant =
                merchantService.create(
                        request.name(),
                        request.callbackUrl(),
                        request.returnUrlPattern(),
                        request.cancelUrlPattern(),
                        request.branding(),
                        request.mode());
        return MerchantMapper.toResponse(merchant);
    }

    @PostMapping("/merchants/{id}/api-keys")
    @ResponseStatus(HttpStatus.CREATED)
    public IssueApiKeyResponse issueApiKey(
            @PathVariable String id, @RequestBody(required = false) IssueApiKeyRequest request) {
        String label = request != null ? request.label() : null;
        return ApiKeyMapper.toResponse(apiKeyService.issue(id, label));
    }

    @PostMapping("/merchants/{id}/webhook-secret")
    public RotateWebhookSecretResponse rotateWebhookSecret(@PathVariable String id) {
        return WebhookSecretMapper.toResponse(webhookSecretService.rotate(id));
    }
}
