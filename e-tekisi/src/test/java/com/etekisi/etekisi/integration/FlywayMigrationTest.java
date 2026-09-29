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
                .schemas("agent_schema", "commuter_schema", "driver_schema")
                .createSchemas(true)
                .locations("classpath:db/migration")
                .load();

        var result = flyway.migrate();
        assertThat(result.success).isTrue();
        // V1 (init) .. V8 (money / ledger / idempotency) — bump as we add more.
        assertThat(result.migrationsExecuted).isGreaterThanOrEqualTo(8);

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

            // Core tables (V7) — previously only created by Hibernate ddl-auto.
            for (String table : new String[]{
                    "commuter_schema.users", "commuter_schema.bookings", "commuter_schema.wallets",
                    "commuter_schema.wallet_transactions", "commuter_schema.notifications",
                    "commuter_schema.messages", "driver_schema.drivers", "driver_schema.payout_requests",
                    "driver_schema.queue_tickets", "agent_schema.staff_users"}) {
                try (ResultSet rs = s.executeQuery("select to_regclass('" + table + "')")) {
                    rs.next();
                    assertThat(rs.getString(1)).as(table).isEqualTo(table);
                }
            }

            // Money is exact decimal, not floating point (V7/V8).
            try (ResultSet rs = s.executeQuery(
                    "select data_type from information_schema.columns " +
                    "where table_schema = 'commuter_schema' and table_name = 'wallets' and column_name = 'balance'")) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("numeric");
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
