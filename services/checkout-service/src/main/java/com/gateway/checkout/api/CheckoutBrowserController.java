package com.gateway.checkout.api;

import com.gateway.checkout.api.dto.CancelCheckoutResponse;
import com.gateway.checkout.api.dto.CompleteCheckoutRequest;
import com.gateway.checkout.api.dto.CompleteCheckoutResponse;
import com.gateway.checkout.api.dto.SessionDetailsResponse;
import com.gateway.checkout.domain.BrowserCheckoutService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/checkout/{id}")
public class CheckoutBrowserController {
    private final BrowserCheckoutService service;

    public CheckoutBrowserController(BrowserCheckoutService service) {
        this.service = service;
    }

    @GetMapping("/session-details")
    public ResponseEntity<SessionDetailsResponse> details(
            @PathVariable String id,
            @RequestHeader(value = "X-Checkout-Session-Secret", required = false) String secret) {
        return noStore(service.details(id, secret));
    }

    @PostMapping("/complete")
    public ResponseEntity<CompleteCheckoutResponse> complete(
            @PathVariable String id,
            @RequestHeader(value = "X-Checkout-Session-Secret", required = false) String secret,
            @Valid @RequestBody CompleteCheckoutRequest request) {
        return noStore(service.complete(id, secret, request.token()));
    }

    @PostMapping("/cancel")
    public ResponseEntity<CancelCheckoutResponse> cancel(
            @PathVariable String id,
            @RequestHeader(value = "X-Checkout-Session-Secret", required = false) String secret) {
        return noStore(service.cancel(id, secret));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
