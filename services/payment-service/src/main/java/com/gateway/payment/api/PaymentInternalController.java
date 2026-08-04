package com.gateway.payment.api;

import com.gateway.payment.api.dto.CreatePaymentRequest;
import com.gateway.payment.api.dto.PaymentResponse;
import com.gateway.payment.domain.PaymentAuthorizationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal endpoint for authorizing a payment.
 *
 * <p>Called by checkout-service (service-mesh only) after the consumer submits the card form.
 * Protected by {@code InternalCallerAuthenticationFilter} — callers must send {@code
 * X-Caller-Service} and {@code X-Internal-Token}.
 */
@RestController
@RequestMapping("/internal/v1")
public class PaymentInternalController {

    private final PaymentAuthorizationService authorizationService;

    public PaymentInternalController(PaymentAuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    /**
     * Authorize a payment.
     *
     * <p>Returns 201 with the payment in its terminal state (CAPTURED or FAILED). The caller should
     * inspect {@code status} and branch accordingly. A declined card is still a 201 — the API call
     * itself succeeded; only a 503 means the outcome is unknown (acquirer timeout).
     */
    @PostMapping("/payments")
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentResponse authorizePayment(@Valid @RequestBody CreatePaymentRequest request) {
        return authorizationService.charge(request);
    }
}
