package com.gateway.acquirer.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.acquirer.api.dto.AuthorizeRequest;
import com.gateway.acquirer.api.dto.AuthorizeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AcquirerServiceTest {

    private AcquirerService service;
    private RequestLog requestLog;

    @BeforeEach
    void setUp() {
        requestLog = new RequestLog();
        service = new AcquirerService(requestLog, 0L);
    }

    @Test
    void visaApproveCardReturnsApproved() {
        AuthorizeResult result = service.authorize(authorizeRequest("4242424242424242"));

        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.response().outcome()).isEqualTo(AcquirerOutcome.APPROVED);
        assertThat(result.response().authCode()).isNotBlank();
        assertThat(result.response().acquirerReference()).startsWith("acq_test_");
        assertThat(result.response().errorCode()).isNull();
    }

    @Test
    void mastercardApproveCardReturnsApproved() {
        AuthorizeResult result = service.authorize(authorizeRequest("5555555555554444"));

        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.response().outcome()).isEqualTo(AcquirerOutcome.APPROVED);
        assertThat(result.response().authCode()).isNotBlank();
    }

    @Test
    void declineCardReturnsDeclinedWithCardDeclinedCode() {
        AuthorizeResult result = service.authorize(authorizeRequest("4000000000000002"));

        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.response().outcome()).isEqualTo(AcquirerOutcome.DECLINED);
        assertThat(result.response().errorCode()).isEqualTo("card_declined");
        assertThat(result.response().authCode()).isNull();
        assertThat(result.response().acquirerReference()).isNull();
    }

    @Test
    void insufficientFundsCardReturnsDeclinedWithInsufficientFundsCode() {
        AuthorizeResult result = service.authorize(authorizeRequest("4000000000009995"));

        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.response().outcome()).isEqualTo(AcquirerOutcome.DECLINED);
        assertThat(result.response().errorCode()).isEqualTo("insufficient_funds");
    }

    @Test
    void timeoutCardReturns504WithAcquirerTimeoutCode() {
        AuthorizeResult result = service.authorize(authorizeRequest("4000000000000341"));

        assertThat(result.httpStatus()).isEqualTo(504);
        assertThat(result.response().outcome()).isEqualTo(AcquirerOutcome.ERROR);
        assertThat(result.response().errorCode()).isEqualTo("acquirer_timeout");
        assertThat(result.response().authCode()).isNull();
    }

    @Test
    void processingErrorCardReturnsErrorWithProcessingErrorCode() {
        AuthorizeResult result = service.authorize(authorizeRequest("4000000000000119"));

        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.response().outcome()).isEqualTo(AcquirerOutcome.ERROR);
        assertThat(result.response().errorCode()).isEqualTo("processing_error");
    }

    @Test
    void unknownPanReturnsDeclined() {
        AuthorizeResult result = service.authorize(authorizeRequest("4111111111111111"));

        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.response().outcome()).isEqualTo(AcquirerOutcome.DECLINED);
        assertThat(result.response().errorCode()).isEqualTo("card_declined");
    }

    @Test
    void panWithSpacesIsNormalisedBeforeLookup() {
        AuthorizeResult result = service.authorize(authorizeRequest("4242 4242 4242 4242"));

        assertThat(result.response().outcome()).isEqualTo(AcquirerOutcome.APPROVED);
    }

    @Test
    void eachApprovedResponseHasUniqueAuthCode() {
        AuthorizeResponse first =
                service.authorize(authorizeRequest("4242424242424242")).response();
        AuthorizeResponse second =
                service.authorize(authorizeRequest("4242424242424242")).response();

        assertThat(first.authCode()).isNotEqualTo(second.authCode());
    }

    @Test
    void authorizeRequestIsRecordedInLog() {
        service.authorize(authorizeRequest("4242424242424242"));

        assertThat(requestLog.snapshot()).hasSize(1);
        assertThat(requestLog.snapshot().get(0).last4()).isEqualTo("4242");
        assertThat(requestLog.snapshot().get(0).outcome()).isEqualTo(AcquirerOutcome.APPROVED);
    }

    @Test
    void logIsCapedAt100Entries() {
        for (int i = 0; i < 110; i++) {
            service.authorize(authorizeRequest("4242424242424242"));
        }

        assertThat(requestLog.snapshot()).hasSize(100);
    }

    private static AuthorizeRequest authorizeRequest(String pan) {
        return new AuthorizeRequest(pan, 12, 2027, "123", 1000L, "DKK", "ref_test");
    }
}
