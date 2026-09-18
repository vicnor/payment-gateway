package com.gateway.shared.web.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gateway.shared.web.error.ErrorEnvelope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

@Order(Ordered.HIGHEST_PRECEDENCE + 7)
@Slf4j
public final class IdempotencyFilter extends OncePerRequestFilter {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAY_HEADER = "Idempotent-Replay";

    private final IdempotencyStore store;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration lease;
    private final Duration retention;
    private final AntPathMatcher matcher = new AntPathMatcher();

    public IdempotencyFilter(IdempotencyStore store, ObjectMapper objectMapper) {
        this(store, objectMapper, Clock.systemUTC(), Duration.ofSeconds(60), Duration.ofHours(24));
    }

    public IdempotencyFilter(
            IdempotencyStore store,
            ObjectMapper objectMapper,
            Clock clock,
            Duration lease,
            Duration retention) {
        this.store = store;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.lease = lease;
        this.retention = retention;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod())
                || !matcher.match("/v1/**", resolvedPath(request));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Object merchant = request.getAttribute("merchant.principal");
        if (merchant == null) {
            chain.doFilter(request, response);
            return;
        }

        String suppliedKey = request.getHeader(HEADER);
        String normalizedKey = normalizeUuid(suppliedKey);
        if (normalizedKey == null) {
            writeError(
                    response,
                    400,
                    "validation_error",
                    suppliedKey == null ? "missing_idempotency_key" : "invalid_idempotency_key",
                    "A valid UUID Idempotency-Key header is required.",
                    HEADER);
            return;
        }

        byte[] body = request.getInputStream().readAllBytes();
        String path = resolvedPath(request);
        String merchantId = merchantId(merchant);
        String storageKey =
                sha256(
                        merchantId
                                + "\n"
                                + request.getMethod()
                                + "\n"
                                + path
                                + "\n"
                                + normalizedKey);
        String requestHash = sha256(body);
        String ownerToken = UUID.randomUUID().toString();
        long now = clock.instant().getEpochSecond();
        IdempotencyStore.Claim claim =
                new IdempotencyStore.Claim(
                        storageKey,
                        requestHash,
                        ownerToken,
                        now,
                        now + lease.toSeconds(),
                        now + retention.toSeconds());

        IdempotencyStore.ClaimResult result;
        try {
            result = store.claim(claim);
        } catch (RuntimeException ex) {
            log.warn(
                    "Idempotency claim failed [requestId={}, path={}]",
                    MDC.get("requestId"),
                    path,
                    ex);
            writeUnavailable(response);
            return;
        }
        if (result instanceof IdempotencyStore.ClaimResult.Replay replay) {
            writeReplay(response, replay.response());
            return;
        }
        if (result instanceof IdempotencyStore.ClaimResult.Conflict) {
            writeError(
                    response,
                    409,
                    "idempotency_key_conflict",
                    "idempotency_key_conflict",
                    "An idempotency key was reused with a different request body.",
                    null);
            return;
        }
        if (result instanceof IdempotencyStore.ClaimResult.InProgress) {
            writeError(
                    response,
                    409,
                    "conflict_error",
                    "idempotency_request_in_progress",
                    "A request with this idempotency key is already in progress.",
                    null);
            return;
        }

        IdempotencyContext context = new IdempotencyContext(storageKey, requestHash, ownerToken);
        request.setAttribute(IdempotencyContext.REQUEST_ATTRIBUTE, context);
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(new CachedBodyRequest(request, body), wrappedResponse);
            if (wrappedResponse.getStatus() >= 200 && wrappedResponse.getStatus() < 300) {
                if (!context.isCompleted()) {
                    try {
                        store.complete(
                                context,
                                new IdempotencyStore.CachedResponse(
                                        wrappedResponse.getStatus(),
                                        wrappedResponse.getContentType(),
                                        java.util.Map.of(),
                                        wrappedResponse.getContentAsByteArray()));
                        context.markCompleted();
                    } catch (RuntimeException ex) {
                        log.warn(
                                "Idempotency completion failed [requestId={}, path={}]",
                                MDC.get("requestId"),
                                path,
                                ex);
                        safeRelease(context, path);
                        wrappedResponse.resetBuffer();
                        writeUnavailable(wrappedResponse);
                    }
                }
            } else if (!context.isCompleted()) {
                safeRelease(context, path);
            }
        } catch (RuntimeException | ServletException | IOException ex) {
            if (!context.isCompleted()) {
                safeRelease(context, path);
            }
            throw ex;
        } finally {
            wrappedResponse.copyBodyToResponse();
        }
    }

    private void safeRelease(IdempotencyContext context, String path) {
        try {
            store.release(context);
        } catch (RuntimeException ex) {
            log.warn(
                    "Idempotency release failed [requestId={}, path={}]",
                    MDC.get("requestId"),
                    path,
                    ex);
        }
    }

    private void writeUnavailable(HttpServletResponse response) throws IOException {
        writeError(
                response,
                503,
                "api_error",
                "idempotency_store_unavailable",
                "Idempotency storage is temporarily unavailable.",
                null);
    }

    private void writeReplay(HttpServletResponse response, IdempotencyStore.CachedResponse cached)
            throws IOException {
        response.setStatus(cached.status());
        response.setHeader(REPLAY_HEADER, "true");
        if (cached.contentType() != null) {
            response.setContentType(cached.contentType());
        }
        cached.headers().forEach(response::setHeader);
        response.getOutputStream().write(cached.body());
    }

    private void writeError(
            HttpServletResponse response,
            int status,
            String type,
            String code,
            String message,
            String param)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getWriter(),
                ErrorEnvelope.of(type, code, message, param, MDC.get("requestId")));
    }

    private static String normalizeUuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            UUID parsed = UUID.fromString(value);
            String canonical = parsed.toString();
            return canonical.equalsIgnoreCase(value) ? canonical.toLowerCase(Locale.ROOT) : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String merchantId(Object principal) {
        try {
            return (String) principal.getClass().getMethod("merchantId").invoke(principal);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(
                    "Authenticated principal does not expose merchantId", ex);
        }
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static String resolvedPath(HttpServletRequest request) {
        String contextPath = request.getContextPath();
        String uri = request.getRequestURI();
        return contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)
                ? uri.substring(contextPath.length())
                : uri;
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public jakarta.servlet.ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new jakarta.servlet.ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(jakarta.servlet.ReadListener readListener) {}

                @Override
                public int read() {
                    return input.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(
                    new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
