package com.gateway.shared.testing;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.localstack.LocalStackContainer.Service;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Combines {@link AbstractPostgresIT} and {@link AbstractLocalStackIT} for services that need both
 * a relational store and SNS/SQS in the same integration test (e.g. an outbox publisher).
 */
@Testcontainers
public abstract class AbstractPostgresLocalStackIT {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.2"))
                    .withServices(Service.SNS, Service.SQS, Service.KMS, Service.SECRETSMANAGER);

    static {
        POSTGRES.start();
        LOCALSTACK.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        registry.add("spring.cloud.aws.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        registry.add("spring.cloud.aws.region.static", () -> "eu-north-1");
        registry.add("spring.cloud.aws.credentials.access-key", () -> "test");
        registry.add("spring.cloud.aws.credentials.secret-key", () -> "test");
    }

    public static String snsEndpoint() {
        return LOCALSTACK.getEndpointOverride(Service.SNS).toString();
    }

    public static String sqsEndpoint() {
        return LOCALSTACK.getEndpointOverride(Service.SQS).toString();
    }

    public static LocalStackContainer localstack() {
        return LOCALSTACK;
    }
}
