package com.gateway.merchant.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.merchant.MerchantServiceApplication;
import com.gateway.shared.testing.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
        classes = MerchantServiceApplication.class,
        webEnvironment = WebEnvironment.RANDOM_PORT)
class MerchantServiceIT extends AbstractPostgresIT {

    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private TestRestTemplate restTemplate;

    @Test
    void migrationCreatesAllTables() {
        Long merchantsTableCount =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM information_schema.tables WHERE table_name = 'merchants'",
                        Long.class);
        Long apiKeysTableCount =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM information_schema.tables WHERE table_name = 'api_keys'",
                        Long.class);
        Long webhookSecretsTableCount =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM information_schema.tables WHERE table_name = 'webhook_secrets'",
                        Long.class);
        assertThat(merchantsTableCount).isEqualTo(1L);
        assertThat(apiKeysTableCount).isEqualTo(1L);
        assertThat(webhookSecretsTableCount).isEqualTo(1L);
    }

    @Test
    void healthEndpointReturnsUp() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isNotNull();
    }
}
