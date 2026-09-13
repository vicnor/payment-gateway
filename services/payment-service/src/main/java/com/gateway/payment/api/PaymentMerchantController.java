package com.gateway.payment.api;

import com.gateway.payment.api.dto.PaymentListResponse;
import com.gateway.payment.api.dto.PaymentResponse;
import com.gateway.payment.domain.PaymentQueryService;
import com.gateway.shared.security.MerchantPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public, API-key-authenticated Merchant API for reading payments. */
@RestController
@RequestMapping("/v1/payments")
public class PaymentMerchantController {

    private static final String MERCHANT_PRINCIPAL_ATTRIBUTE = "merchant.principal";

    private final PaymentQueryService paymentQueryService;

    public PaymentMerchantController(PaymentQueryService paymentQueryService) {
        this.paymentQueryService = paymentQueryService;
    }

    @GetMapping("/{id}")
    public PaymentResponse retrieve(
            @PathVariable String id,
            @RequestAttribute(MERCHANT_PRINCIPAL_ATTRIBUTE) MerchantPrincipal principal) {
        return paymentQueryService.retrieve(principal.merchantId(), id);
    }

    @GetMapping
    public PaymentListResponse list(
            @RequestParam(required = false) String limit,
            @RequestParam(name = "starting_after", required = false) String startingAfter,
            @RequestParam(name = "created.gte", required = false) String createdGte,
            @RequestParam(name = "created.lte", required = false) String createdLte,
            @RequestAttribute(MERCHANT_PRINCIPAL_ATTRIBUTE) MerchantPrincipal principal) {
        return paymentQueryService.list(
                principal.merchantId(), limit, startingAfter, createdGte, createdLte);
    }
}
