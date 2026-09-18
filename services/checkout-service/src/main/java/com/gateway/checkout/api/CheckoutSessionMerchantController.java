package com.gateway.checkout.api;

import com.gateway.checkout.api.dto.CheckoutSessionResponse;
import com.gateway.checkout.api.dto.CreateCheckoutSessionRequest;
import com.gateway.checkout.domain.CheckoutSessionService;
import com.gateway.shared.security.MerchantPrincipal;
import com.gateway.shared.web.idempotency.IdempotencyContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/checkout-sessions")
public class CheckoutSessionMerchantController {
    private final CheckoutSessionService service;

    public CheckoutSessionMerchantController(CheckoutSessionService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CheckoutSessionResponse create(
            @Valid @RequestBody CreateCheckoutSessionRequest request,
            @RequestAttribute("merchant.principal") MerchantPrincipal principal,
            @RequestAttribute(IdempotencyContext.REQUEST_ATTRIBUTE)
                    IdempotencyContext idempotency) {
        return service.create(request, principal, idempotency);
    }

    @GetMapping("/{id}")
    public CheckoutSessionResponse retrieve(
            @PathVariable String id,
            @RequestAttribute("merchant.principal") MerchantPrincipal principal) {
        return service.retrieve(principal.merchantId(), id);
    }

    @PostMapping("/{id}/cancel")
    public CheckoutSessionResponse cancel(
            @PathVariable String id,
            @RequestAttribute("merchant.principal") MerchantPrincipal principal) {
        return service.cancel(principal.merchantId(), id);
    }
}
