package com.gateway.shared.testing;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.localstack.LocalStackContainer.Service;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
public abstract class AbstractLocalStackIT {

  private static final LocalStackContainer LOCALSTACK =
      new LocalStackContainer(DockerImageName.parse("localstack/localstack:latest"))
          .withServices(Service.SNS, Service.SQS, Service.KMS, Service.SECRETSMANAGER);

  static {
    LOCALSTACK.start();
  }

  @DynamicPropertySource
  static void localStackProperties(DynamicPropertyRegistry registry) {
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

  public static String kmsEndpoint() {
    return LOCALSTACK.getEndpointOverride(Service.KMS).toString();
  }
}
