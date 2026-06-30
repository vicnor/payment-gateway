package com.gateway.acquirer.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.gateway.acquirer.domain.AcquirerOutcome;

@JsonInclude(Include.NON_NULL)
public record AuthorizeResponse(
        AcquirerOutcome outcome, String authCode, String acquirerReference, String errorCode) {}
