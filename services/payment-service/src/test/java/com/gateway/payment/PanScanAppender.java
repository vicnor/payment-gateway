package com.gateway.payment;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Test-only Logback appender that fails if any log line contains a PAN-length numeric sequence.
 *
 * <p>Attach to the root logger before exercising the payment flow, then assert that {@link
 * #getViolations()} is empty. A match against {@code \b\d{13,19}\b} is treated as a PCI violation —
 * payment-service must never log the raw PAN received from detokenize.
 */
public class PanScanAppender extends AppenderBase<ILoggingEvent> {

    private static final Pattern PAN_PATTERN = Pattern.compile("\\b\\d{13,19}\\b");

    private final List<String> violations = Collections.synchronizedList(new ArrayList<>());

    @Override
    protected void append(ILoggingEvent event) {
        String message = event.getFormattedMessage();
        if (PAN_PATTERN.matcher(message).find()) {
            violations.add(message);
        }
    }

    public void reset() {
        violations.clear();
    }

    public List<String> getViolations() {
        return List.copyOf(violations);
    }
}
