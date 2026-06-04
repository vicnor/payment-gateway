package com.gateway.shared.testing;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public abstract class AbstractDynamoIT {

  private static final int DYNAMO_PORT = 8000;

  @SuppressWarnings("resource")
  private static final GenericContainer<?> DYNAMO =
      new GenericContainer<>(DockerImageName.parse("amazon/dynamodb-local:latest"))
          .withExposedPorts(DYNAMO_PORT)
          .withCommand("-jar DynamoDBLocal.jar -inMemory -sharedDb");

  static {
    DYNAMO.start();
  }

  @DynamicPropertySource
  static void dynamoProperties(DynamicPropertyRegistry registry) {
    registry.add("gateway.dynamodb.endpoint", AbstractDynamoIT::dynamoEndpoint);
  }

  public static String dynamoEndpoint() {
    return "http://" + DYNAMO.getHost() + ":" + DYNAMO.getMappedPort(DYNAMO_PORT);
  }
}
