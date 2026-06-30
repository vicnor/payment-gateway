package com.gateway.acquirer.domain;

import com.gateway.acquirer.api.dto.LoggedRequest;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

public class RequestLog {

    private static final int MAX_ENTRIES = 100;

    private final Deque<LoggedRequest> entries = new ArrayDeque<>();

    public synchronized void add(LoggedRequest entry) {
        entries.addFirst(entry);
        if (entries.size() > MAX_ENTRIES) {
            entries.removeLast();
        }
    }

    public synchronized List<LoggedRequest> snapshot() {
        return List.copyOf(entries);
    }
}
