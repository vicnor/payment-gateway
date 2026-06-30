package com.gateway.payment.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.payment.PaymentServiceApplication;
import com.gateway.shared.testing.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        classes = PaymentServiceApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT)
class PaymentServiceIT extends AbstractPostgresIT {

    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private TestRestTemplate restTemplate;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Activate the ApiKeyAuthenticationFilter bean chain so the filter wiring is tested.
        // The merchant-service is not running; the filter rejects requests before any call is made.
        registry.add("shared.security.merchant-service.base-url", () -> "http://localhost:18101");
    }

    @Test
    void migrationCreatesAllTables() {
        Long paymentsCount =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM information_schema.tables WHERE table_name = 'payments'",
                        Long.class);
        Long attemptsCount =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM information_schema.tables WHERE table_name = 'payment_attempts'",
                        Long.class);
        Long eventsCount =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM information_schema.tables WHERE table_name = 'payment_events'",
                        Long.class);
        Long outboxCount =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM information_schema.tables WHERE table_name = 'outbox'",
                        Long.class);

        assertThat(paymentsCount).isEqualTo(1L);
        assertThat(attemptsCount).isEqualTo(1L);
        assertThat(eventsCount).isEqualTo(1L);
        assertThat(outboxCount).isEqualTo(1L);
    }

    @Test
    void healthEndpointReturnsUp() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isNotNull();
    }

    @Test
    void protectedPathRequiresApiKey() {
        // No Authorization header — filter must reject before routing (no controller needed).
        ResponseEntity<String> response = restTemplate.getForEntity("/v1/ping", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).contains("authentication_error");
    }
}
