package com.gateway.acquirer.domain;

import com.gateway.acquirer.api.dto.AuthorizeRequest;
import com.gateway.acquirer.api.dto.AuthorizeResponse;
import com.gateway.acquirer.api.dto.LoggedRequest;
import com.github.f4b6a3.ulid.UlidCreator;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class AcquirerService {

    private final RequestLog requestLog;
    private final long timeoutMillis;

    public AcquirerService(RequestLog requestLog, long timeoutMillis) {
        this.requestLog = requestLog;
        this.timeoutMillis = timeoutMillis;
    }

    public AuthorizeResult authorize(AuthorizeRequest request) {
        String pan = request.pan().replaceAll("[ \\-]", "");
        String last4 = pan.length() >= 4 ? pan.substring(pan.length() - 4) : pan;

        Optional<TestCard> match = TestCard.forPan(pan);

        AcquirerOutcome outcome;
        String errorCode;
        boolean isTimeout = false;

        if (match.isEmpty()) {
            outcome = AcquirerOutcome.DECLINED;
            errorCode = "card_declined";
        } else {
            TestCard card = match.get();
            if (card.isTimeout()) {
                isTimeout = true;
                try {
                    Thread.sleep(timeoutMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            outcome = card.outcome();
            errorCode = card.errorCode();
        }

        AuthorizeResponse response = buildResponse(outcome, errorCode);
        requestLog.add(
                new LoggedRequest(
                        Instant.now(),
                        last4,
                        request.amount(),
                        request.currency(),
                        request.reference(),
                        outcome));

        return isTimeout ? AuthorizeResult.gatewayTimeout(response) : AuthorizeResult.ok(response);
    }

    public List<LoggedRequest> getLog() {
        return requestLog.snapshot();
    }

    private static AuthorizeResponse buildResponse(AcquirerOutcome outcome, String errorCode) {
        if (outcome == AcquirerOutcome.APPROVED) {
            String authCode =
                    UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
            String acquirerRef = "acq_test_" + UlidCreator.getUlid().toString();
            return new AuthorizeResponse(outcome, authCode, acquirerRef, null);
        }
        return new AuthorizeResponse(outcome, null, null, errorCode);
    }
}
