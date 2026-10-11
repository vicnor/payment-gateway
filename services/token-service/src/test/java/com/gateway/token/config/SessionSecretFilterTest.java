package com.gateway.token.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gateway.token.client.CheckoutSessionClient;
import com.gateway.token.client.CheckoutSessionClient.AuthenticatedSession;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SessionSecretFilterTest {
    @Test
    void authenticatesAndExposesMerchantContext() throws Exception {
        CheckoutSessionClient client = mock(CheckoutSessionClient.class);
        when(client.authenticate(eq("cs_test"), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new AuthenticatedSession("cs_test", "mer_test", 2000));
        SessionSecretFilter filter = new SessionSecretFilter(client);
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/checkout/cs_test/tokens");
        request.addHeader("X-Checkout-Session-Secret", "secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(request.getAttribute(SessionSecretFilter.MERCHANT_ID_ATTRIBUTE))
                .isEqualTo("mer_test");
        verify(chain).doFilter(request, response);
    }

    @Test
    void missingSecretIsUnauthorized() throws Exception {
        SessionSecretFilter filter = new SessionSecretFilter(mock(CheckoutSessionClient.class));
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/checkout/cs_test/tokens");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("missing_session_secret");
    }
}
