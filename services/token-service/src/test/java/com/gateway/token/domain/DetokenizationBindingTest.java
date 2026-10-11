package com.gateway.token.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gateway.shared.web.error.ConflictException;
import com.gateway.token.persistence.DataKeyStore;
import com.gateway.token.persistence.TokenItem;
import com.gateway.token.persistence.TokenStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.kms.KmsClient;

class DetokenizationBindingTest {
    @Test
    void sessionMismatchDoesNotConsumeToken() {
        TokenStore tokens = mock(TokenStore.class);
        TokenItem item = new TokenItem();
        item.setToken("tok_test");
        item.setSessionId("cs_owner");
        item.setMerchantId("mer_owner");
        item.setExpiresAt(2000L);
        when(tokens.findById("tok_test")).thenReturn(Optional.of(item));
        DetokenizationService service =
                new DetokenizationService(
                        tokens,
                        mock(DataKeyStore.class),
                        mock(KmsClient.class),
                        Clock.fixed(Instant.ofEpochSecond(1000), ZoneOffset.UTC));

        assertThatThrownBy(
                        () ->
                                service.detokenize(
                                        "tok_test", "payment-service", "cs_other", "mer_owner"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("does not belong");
        verify(tokens, never()).markUsed("tok_test");
    }
}
