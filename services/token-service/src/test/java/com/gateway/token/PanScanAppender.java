package com.gateway.token;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Test-only Logback appender that captures log messages containing PAN-length numeric sequences.
 *
 * <p>Attach to the root logger before exercising a tokenize/detokenize path, then assert that
 * {@link #getViolations()} is empty. A match against {@code \b\d{13,19}\b} is treated as a PCI
 * violation — no log line at any level should contain the raw PAN.
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

    /** Clear captured violations — call before each sensitive operation under test. */
    public void reset() {
        violations.clear();
    }

    /** Returns an immutable snapshot of all captured violation messages. */
    public List<String> getViolations() {
        return List.copyOf(violations);
    }
}
