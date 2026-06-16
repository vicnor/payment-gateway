package com.gateway.token;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Test-only Logback appender that captures messages written to the {@code com.gateway.token.audit}
 * logger.
 *
 * <p>Attach to the audit logger before exercising a detokenize path, then assert that {@link
 * #getEntries()} contains the expected audit record.
 */
public class AuditCaptureAppender extends AppenderBase<ILoggingEvent> {

    private final List<String> entries = Collections.synchronizedList(new ArrayList<>());

    @Override
    protected void append(ILoggingEvent event) {
        entries.add(event.getFormattedMessage());
    }

    public List<String> getEntries() {
        return List.copyOf(entries);
    }

    public void reset() {
        entries.clear();
    }
}
