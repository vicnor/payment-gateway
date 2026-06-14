package com.gateway.shared.testing;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.localstack.LocalStackContainer.Service;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration test base that provides both DynamoDB-Local and KMS (via LocalStack).
 *
 * <p>Registers the following Spring properties so services can point their hand-wired AWS SDK v2
 * clients at the containers:
 *
 * <ul>
 *   <li>{@code gateway.aws.region} — {@code eu-north-1}
 *   <li>{@code gateway.aws.credentials.access-key} / {@code .secret-key} — {@code test/test}
 *   <li>{@code gateway.aws.dynamodb.endpoint} — DynamoDB-Local mapped port
 *   <li>{@code gateway.aws.kms.endpoint} — LocalStack KMS mapped port
 * </ul>
 *
 * <p>Note: DynamoDB tables are NOT created by this base — they must be created by the test class
 * using the application's own {@code TableSchema} definitions (single source of truth).
 */
@Testcontainers
public abstract class AbstractDynamoKmsIT {

    private static final int DYNAMO_PORT = 8000;

    @SuppressWarnings("resource")
    private static final GenericContainer<?> DYNAMO =
            new GenericContainer<>(DockerImageName.parse("amazon/dynamodb-local:2.5.2"))
                    .withExposedPorts(DYNAMO_PORT)
                    .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb");

    private static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.2"))
                    .withServices(Service.KMS);

    static {
        DYNAMO.start();
        LOCALSTACK.start();
    }

    @DynamicPropertySource
    static void awsProperties(DynamicPropertyRegistry registry) {
        registry.add("gateway.aws.region", () -> "eu-north-1");
        registry.add("gateway.aws.credentials.access-key", () -> "test");
        registry.add("gateway.aws.credentials.secret-key", () -> "test");
        registry.add("gateway.aws.dynamodb.endpoint", AbstractDynamoKmsIT::dynamoEndpoint);
        registry.add("gateway.aws.kms.endpoint", AbstractDynamoKmsIT::kmsEndpoint);
    }

    public static String dynamoEndpoint() {
        return "http://" + DYNAMO.getHost() + ":" + DYNAMO.getMappedPort(DYNAMO_PORT);
    }

    public static String kmsEndpoint() {
        return LOCALSTACK.getEndpointOverride(Service.KMS).toString();
    }
}
