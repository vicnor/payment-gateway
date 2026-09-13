package com.gateway.payment.domain;

import com.gateway.payment.api.dto.PaymentListResponse;
import com.gateway.payment.api.dto.PaymentResponse;
import com.gateway.payment.api.mapper.PaymentMapper;
import com.gateway.payment.persistence.PaymentQueryRepository;
import com.gateway.payment.persistence.PaymentRepository;
import com.gateway.shared.web.error.NotFoundException;
import com.gateway.shared.web.error.ValidationException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only, merchant-scoped payment queries for the public Merchant API. */
@Service
@Transactional(readOnly = true)
public class PaymentQueryService {

    static final int DEFAULT_LIMIT = 25;
    static final int MAX_LIMIT = 100;
    private static final String LIST_URL = "/v1/payments";
    private static final Pattern PAYMENT_ID = Pattern.compile("^pay_[0-9A-HJKMNP-TV-Z]{26}$");

    private final PaymentRepository paymentRepository;
    private final PaymentQueryRepository paymentQueryRepository;

    public PaymentQueryService(
            PaymentRepository paymentRepository, PaymentQueryRepository paymentQueryRepository) {
        this.paymentRepository = paymentRepository;
        this.paymentQueryRepository = paymentQueryRepository;
    }

    public PaymentResponse retrieve(String merchantId, String paymentId) {
        return paymentRepository
                .findByExternalIdAndMerchantId(paymentId, merchantId)
                .map(PaymentMapper::toResponse)
                .orElseThrow(() -> new NotFoundException("Payment", paymentId));
    }

    public PaymentListResponse list(
            String merchantId,
            String limitValue,
            String startingAfter,
            String createdGteValue,
            String createdLteValue) {
        int limit = parseLimit(limitValue);
        Instant createdGte = parseEpochSeconds(createdGteValue, "created.gte");
        Instant createdLte = parseEpochSeconds(createdLteValue, "created.lte");
        if (createdGte != null && createdLte != null && createdGte.isAfter(createdLte)) {
            throw new ValidationException(
                    "invalid_created_range",
                    "created.gte must be less than or equal to created.lte.",
                    "created.gte");
        }

        Payment cursor = resolveCursor(merchantId, startingAfter, createdGte, createdLte);
        Instant cursorCreatedAt = cursor != null ? cursor.getCreatedAt() : null;
        String cursorExternalId = cursor != null ? cursor.getExternalId() : null;

        List<Payment> rows =
                paymentQueryRepository.findMerchantPage(
                        merchantId,
                        createdGte,
                        createdLte,
                        cursorCreatedAt,
                        cursorExternalId,
                        limit + 1);
        boolean hasMore = rows.size() > limit;
        List<PaymentResponse> data =
                rows.stream().limit(limit).map(PaymentMapper::toResponse).toList();
        return new PaymentListResponse("list", data, hasMore, LIST_URL);
    }

    private Payment resolveCursor(
            String merchantId, String startingAfter, Instant createdGte, Instant createdLte) {
        if (startingAfter == null) {
            return null;
        }
        if (!PAYMENT_ID.matcher(startingAfter).matches()) {
            throw invalidCursor();
        }

        Payment cursor =
                paymentRepository
                        .findByExternalIdAndMerchantId(startingAfter, merchantId)
                        .orElseThrow(PaymentQueryService::invalidCursor);
        if ((createdGte != null && cursor.getCreatedAt().isBefore(createdGte))
                || (createdLte != null && cursor.getCreatedAt().isAfter(createdLte))) {
            throw invalidCursor();
        }
        return cursor;
    }

    private static int parseLimit(String value) {
        if (value == null) {
            return DEFAULT_LIMIT;
        }
        try {
            int limit = Integer.parseInt(value);
            if (limit < 1 || limit > MAX_LIMIT) {
                throw invalidLimit();
            }
            return limit;
        } catch (NumberFormatException ex) {
            throw invalidLimit();
        }
    }

    private static Instant parseEpochSeconds(String value, String param) {
        if (value == null) {
            return null;
        }
        try {
            long epochSeconds = Long.parseLong(value);
            if (epochSeconds < 0) {
                throw invalidCreated(param);
            }
            return Instant.ofEpochSecond(epochSeconds);
        } catch (NumberFormatException | DateTimeException ex) {
            throw invalidCreated(param);
        }
    }

    private static ValidationException invalidLimit() {
        return new ValidationException(
                "invalid_limit", "limit must be an integer between 1 and 100.", "limit");
    }

    private static ValidationException invalidCreated(String param) {
        return new ValidationException(
                "invalid_created", param + " must be a non-negative Unix timestamp.", param);
    }

    private static ValidationException invalidCursor() {
        return new ValidationException(
                "invalid_cursor", "starting_after is not a valid cursor.", "starting_after");
    }
}
