package com.gateway.acquirer.api.dto;

import com.gateway.acquirer.domain.AcquirerOutcome;
import java.time.Instant;

public record LoggedRequest(
        Instant receivedAt,
        String last4,
        long amount,
        String currency,
        String reference,
        AcquirerOutcome outcome) {}
