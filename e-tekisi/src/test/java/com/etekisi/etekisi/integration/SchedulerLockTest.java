package com.etekisi.etekisi.integration;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scheduled-job lock on the real migrated schema: two instances can't
 * hold the same job's lock at once. Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class SchedulerLockTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplateLockProvider provider(DriverManagerDataSource ds) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(ds))
                .withTableName("agent_schema.shedlock")
                .usingDbTime()
                .build());
    }

    @Test
    void onlyOneInstanceRunsAJobAtATime() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas("agent_schema", "commuter_schema", "driver_schema")
                .createSchemas(true)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        DriverManagerDataSource ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        JdbcTemplateLockProvider instanceA = provider(ds);
        JdbcTemplateLockProvider instanceB = provider(ds);
        LockConfiguration job = new LockConfiguration(Instant.now(), "reconciliation", Duration.ofMinutes(5), Duration.ZERO);

        Optional<SimpleLock> a = instanceA.lock(job);
        assertThat(a).isPresent();
        assertThat(instanceB.lock(job)).isEmpty(); // B skips this run
        a.get().unlock();
        assertThat(instanceB.lock(new LockConfiguration(Instant.now(), "reconciliation", Duration.ofMinutes(5), Duration.ZERO)))
                .isPresent(); // next run, either instance may take it
    }
}
