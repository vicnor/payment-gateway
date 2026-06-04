package com.gateway.shared.web.support;

import com.gateway.shared.web.error.AcquirerUnavailableException;
import com.gateway.shared.web.error.AuthenticationException;
import com.gateway.shared.web.error.IdempotencyConflictException;
import com.gateway.shared.web.error.NotFoundException;
import com.gateway.shared.web.error.PermissionException;
import com.gateway.shared.web.error.RateLimitException;
import com.gateway.shared.web.error.ValidationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TestController {

  @GetMapping("/test/not-found")
  String notFound() {
    throw new NotFoundException("payment", "pay_123");
  }

  @GetMapping("/test/validation")
  String validation() {
    throw new ValidationException("invalid_amount", "Amount must be positive.", "amount");
  }

  @GetMapping("/test/auth")
  String auth() {
    throw new AuthenticationException();
  }

  @GetMapping("/test/permission")
  String permission() {
    throw new PermissionException();
  }

  @GetMapping("/test/idempotency")
  String idempotency() {
    throw new IdempotencyConflictException();
  }

  @GetMapping("/test/rate-limit")
  String rateLimit() {
    throw new RateLimitException();
  }

  @GetMapping("/test/acquirer")
  String acquirer() {
    throw new AcquirerUnavailableException();
  }

  @GetMapping("/test/unexpected")
  String unexpected() {
    throw new RuntimeException("kaboom");
  }

  @PostMapping("/test/validate-body")
  String validateBody(@Valid @RequestBody ValidatedRequest body) {
    return "ok";
  }

  public record ValidatedRequest(@NotBlank(message = "name must not be blank") String name) {}
}
