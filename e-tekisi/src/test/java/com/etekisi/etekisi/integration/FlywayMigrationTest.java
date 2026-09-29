package com.etekisi.etekisi.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2.2 — Testcontainers Flyway migration test.
 *
 * <p>Boots a real Postgres 16 container, applies every checked-in migration
 * under {@code db/migration}, then asserts the key tables exist with the
 * expected columns. Catches migration drift, syntax incompatible with
 * Postgres (vs. H2), and forgotten schema bumps.
 *
 * <p>Skipped automatically on machines without Docker via
 * {@code disabledWithoutDocker = true} so {@code mvn verify} stays green
 * for contributors who haven't installed Docker locally. CI always runs
 * with Docker available.
 */
@Testcontainers(disabledWithoutDocker = true)
class FlywayMigrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("tekisi_test")
                    .withUsername("tekisi")
                    .withPassword("tekisi");

    @Test
    void allCheckedInMigrationsApplyCleanly() throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas("agent_schema")
                .createSchemas(true)
                .locations("classpath:db/migration")
                .load();

        var result = flyway.migrate();
        assertThat(result.success).isTrue();
        // V1 (init) + V2 (insights) + V3 (idempotency) — bump as we add more.
        assertThat(result.migrationsExecuted).isGreaterThanOrEqualTo(3);

        try (Connection c = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement s = c.createStatement()) {

            // decision_audit (V1)
            try (ResultSet rs = s.executeQuery(
                    "select column_name from information_schema.columns " +
                    "where table_schema = 'agent_schema' and table_name = 'decision_audit'")) {
                int cols = 0;
                while (rs.next()) cols++;
                assertThat(cols).as("decision_audit must have columns").isGreaterThan(5);
            }

            // operational_insights (V2)
            try (ResultSet rs = s.executeQuery(
                    "select to_regclass('agent_schema.operational_insights')")) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("agent_schema.operational_insights");
            }

            // idempotency_keys (V3)
            try (ResultSet rs = s.executeQuery(
                    "select to_regclass('agent_schema.idempotency_keys')")) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("agent_schema.idempotency_keys");
            }

            // The unique constraint guards Phase 1.9's correctness contract.
            try (ResultSet rs = s.executeQuery(
                    "select count(*) from pg_constraint " +
                    "where conname = 'uq_idempotency_key_route'")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }
}
