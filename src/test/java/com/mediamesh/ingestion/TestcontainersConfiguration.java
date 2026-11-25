package com.mediamesh.ingestion;

import com.mediamesh.ingestion.config.S3Properties;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;

/**
 * Provides Testcontainers-backed PostgreSQL, Redis, and MinIO instances for integration tests.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    private static final String MINIO_USER = "ingestion";
    private static final String MINIO_PASSWORD = "ingestion";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379);
    }

    @Bean
    GenericContainer<?> s3Container() {
        return new GenericContainer<>(DockerImageName.parse("rustfs/rustfs:latest"))
                .withEnv("RUSTFS_ACCESS_KEY", MINIO_USER)
                .withEnv("RUSTFS_SECRET_KEY", MINIO_PASSWORD)
                .withEnv("RUSTFS_CONSOLE_ENABLE", "false")
                .withExposedPorts(9000)
                .waitingFor(Wait.forListeningPort());
    }

    @Bean
    DynamicPropertyRegistrar s3DynamicProperties(GenericContainer<?> s3Container) {
        return registry -> {
            String endpoint = "http://" + s3Container.getHost() + ":" + s3Container.getMappedPort(9000);
            registry.add("storage.s3.endpoint", () -> endpoint);
            registry.add("storage.s3.public-endpoint", () -> endpoint);
            registry.add("storage.s3.access-key", () -> MINIO_USER);
            registry.add("storage.s3.secret-key", () -> MINIO_PASSWORD);
            registry.add("storage.s3.path-style-access", () -> "true");
        };
    }

    @Bean
    ApplicationRunner minioBucketInitializer(S3Client s3Client, S3Properties s3Properties) {
        return args -> {
            ensureBucket(s3Client, s3Properties.rawBucket());
            ensureBucket(s3Client, s3Properties.processedBucket());
        };
    }

    private static void ensureBucket(S3Client s3Client, String bucket) {
        try {
            s3Client.createBucket(b -> b.bucket(bucket));
        } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException ignored) {
            // Bucket already exists in container
        }
    }
}
