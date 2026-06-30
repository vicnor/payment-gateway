package com.gateway.acquirer.domain;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Test card catalogue. Outcome is determined by the PAN — same contract as
 * docs/architecture/services.md. Extending this catalogue requires updating both this enum and that
 * doc.
 */
public enum TestCard {
    VISA_APPROVE("4242424242424242", AcquirerOutcome.APPROVED, null, false),
    VISA_DECLINE("4000000000000002", AcquirerOutcome.DECLINED, "card_declined", false),
    VISA_INSUFFICIENT_FUNDS(
            "4000000000009995", AcquirerOutcome.DECLINED, "insufficient_funds", false),
    VISA_TIMEOUT("4000000000000341", AcquirerOutcome.ERROR, "acquirer_timeout", true),
    VISA_PROCESSING_ERROR("4000000000000119", AcquirerOutcome.ERROR, "processing_error", false),
    MASTERCARD_APPROVE("5555555555554444", AcquirerOutcome.APPROVED, null, false);

    private static final Map<String, TestCard> BY_PAN = new HashMap<>();

    static {
        for (TestCard card : values()) {
            BY_PAN.put(card.pan, card);
        }
    }

    private final String pan;
    private final AcquirerOutcome outcome;
    private final String errorCode;
    private final boolean timeout;

    TestCard(String pan, AcquirerOutcome outcome, String errorCode, boolean timeout) {
        this.pan = pan;
        this.outcome = outcome;
        this.errorCode = errorCode;
        this.timeout = timeout;
    }

    public static Optional<TestCard> forPan(String pan) {
        return Optional.ofNullable(BY_PAN.get(pan));
    }

    public AcquirerOutcome outcome() {
        return outcome;
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean isTimeout() {
        return timeout;
    }
}
