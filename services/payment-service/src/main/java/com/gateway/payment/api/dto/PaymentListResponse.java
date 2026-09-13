package com.gateway.payment.api.dto;

import java.util.List;

/** Cursor-paginated list of payments returned by the Merchant API. */
public record PaymentListResponse(
        String object, List<PaymentResponse> data, boolean hasMore, String url) {}
