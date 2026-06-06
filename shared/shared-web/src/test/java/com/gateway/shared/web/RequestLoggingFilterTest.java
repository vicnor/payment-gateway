package com.gateway.shared.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gateway.shared.web.config.SharedWebAutoConfiguration;
import com.gateway.shared.web.request.RequestLoggingFilter;
import com.gateway.shared.web.support.EchoController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(EchoController.class)
@Import(SharedWebAutoConfiguration.class)
class RequestLoggingFilterTest {

    @Autowired MockMvc mockMvc;

    private ListAppender<ILoggingEvent> listAppender;
    private Logger filterLogger;

    @BeforeEach
    void attachAppender() {
        filterLogger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
        listAppender = new ListAppender<>();
        listAppender.start();
        filterLogger.addAppender(listAppender);
    }

    @AfterEach
    void detachAppender() {
        filterLogger.detachAppender(listAppender);
    }

    @Test
    void logsOneLineWithMethodPathStatusDuration() throws Exception {
        mockMvc.perform(get("/test/ping")).andExpect(status().isOk());

        var events = listAppender.list;
        assertEquals(1, events.size(), "Expected exactly one log line per request");
        String message = events.getFirst().getFormattedMessage();
        assertTrue(message.contains("method=GET"), "Log should contain method");
        assertTrue(message.contains("path=/test/ping"), "Log should contain path");
        assertTrue(message.contains("status=200"), "Log should contain status");
        assertTrue(message.contains("duration_ms="), "Log should contain duration_ms");
    }

    @Test
    void queryStringIsNeverLogged() throws Exception {
        mockMvc.perform(get("/test/ping").param("k", "supersecret")).andExpect(status().isOk());

        var events = listAppender.list;
        assertFalse(events.isEmpty());
        String message = events.getFirst().getFormattedMessage();
        // Path must not include the query string
        assertFalse(
                message.contains("supersecret"),
                "Query string must never appear in log (could leak session secrets)");
        assertFalse(message.contains("?"), "Query string delimiter must not appear in logged path");
    }
}
