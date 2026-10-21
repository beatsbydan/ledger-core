package com.ledgercore.support;

import java.time.Duration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Real Postgres and real Kafka, not H2/embedded-broker fakes: the invariants under test
 * (deferred constraint triggers, SELECT ... FOR UPDATE / SKIP LOCKED row locking, actual
 * producer/consumer round-trips) are broker- and database-specific behavior a fake can't
 * faithfully emulate.
 *
 * Singleton container pattern for both, deliberately not {@code @Testcontainers}/{@code @Container}:
 * that annotation pair ties a container's lifecycle to each test class and stops it once that
 * class's tests finish - even though these fields are static - which would kill the container
 * out from under every subclass that runs after the first (this bit us for Postgres in Phase 1).
 * Starting them once here, in a static initializer, and never calling stop() keeps one of each
 * alive for the whole test run; the Testcontainers Ryuk reaper cleans them up when the JVM exits.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    // Default Testcontainers startup timeout (60s) is too tight for a JVM-based broker's first
    // boot on this machine - the container was still coming up when the wait strategy gave up.
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0")
            .withStartupTimeout(Duration.ofSeconds(180));

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }
}
