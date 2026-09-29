package com.etekisi.etekisi.integration;

import com.etekisi.etekisi.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the whole application the way production does: Flyway migrates a
 * real Postgres, then Hibernate runs with ddl-auto=validate. If any entity
 * drifts from the SQL migrations, the context fails to start and this test
 * fails. Skipped on machines without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class SchemaValidationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("tekisi_validate")
                    .withUsername("tekisi")
                    .withPassword("tekisi");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        r.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        r.add("spring.flyway.enabled", () -> "true");
        r.add("spring.flyway.schemas", () -> "agent_schema,commuter_schema,driver_schema");
        r.add("spring.flyway.create-schemas", () -> "true");
        r.add("spring.flyway.baseline-on-migrate", () -> "false");
    }

    @Autowired private UserRepository users;

    @Test
    void entitiesMatchTheMigratedSchema() {
        // Reaching here means Flyway migrated and Hibernate validated every entity.
        assertThat(users.count()).isZero();
    }
}
