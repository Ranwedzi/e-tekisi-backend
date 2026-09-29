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
 * An existing dev database built by the old init.sql + ddl-auto=update
 * (int ids, short varchars, double-precision money) must upgrade cleanly:
 * Flyway baselines it, V7 leaves the tables alone and V8 fixes the types
 * without losing data. Skipped on machines without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class LegacyUpgradeMigrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("tekisi_legacy")
                    .withUsername("tekisi")
                    .withPassword("tekisi");

    @Test
    void legacyDatabaseUpgradesWithoutLosingMoney() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            // Roughly what the old init.sql + Hibernate ddl-auto=update produced.
            s.execute("create schema commuter_schema; create schema driver_schema; create schema agent_schema");
            s.execute("create table commuter_schema.users (id serial primary key, full_name varchar(100), "
                    + "email varchar(100) unique, phone_number varchar(15), password varchar(255))");
            s.execute("create table driver_schema.drivers (id serial primary key, full_name varchar(100), "
                    + "taxi_plate varchar(15), license_number varchar(50), is_available boolean default false, "
                    + "balance float8, trips_completed int, phone_number varchar(255), email varchar(255), "
                    + "password varchar(255), is_active boolean not null default false)");
            s.execute("create table commuter_schema.wallets (id bigserial primary key, user_id bigint, "
                    + "role varchar(255), balance float8, total_trips int)");
            s.execute("create table commuter_schema.bookings (id bigserial primary key, commuter_id bigint, "
                    + "driver_id bigint, pickup_location varchar(255), destination varchar(255), "
                    + "scheduled_time varchar(255), fare float8, tip float8, status varchar(255))");
            s.execute("create table commuter_schema.messages (id bigserial primary key, booking_id bigint, "
                    + "sender_id bigint, content varchar(255), \"timestamp\" timestamp(6))");
            s.execute("create table driver_schema.payout_requests (id bigserial primary key, driver_id bigint, "
                    + "amount float8, method varchar(255), bank_name varchar(255), account_number varchar(255), "
                    + "account_holder varchar(255), phone_number varchar(255), status varchar(255), "
                    + "created_at timestamp(6) with time zone)");
            s.execute("insert into commuter_schema.users (full_name, email, phone_number, password) "
                    + "values ('Old User', 'old@test.dev', '0710000000', 'x')");
            s.execute("insert into commuter_schema.wallets (user_id, role, balance, total_trips) "
                    + "values (1, 'COMMUTER', 123.456, 2)");
        }

        var result = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas("agent_schema", "commuter_schema", "driver_schema")
                .createSchemas(true)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        assertThat(result.success).isTrue();

        try (Connection c = connect(); Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("select balance from commuter_schema.wallets where user_id = 1")) {
                rs.next();
                assertThat(rs.getBigDecimal(1)).isEqualByComparingTo("123.46");
            }
            try (ResultSet rs = s.executeQuery("select data_type from information_schema.columns "
                    + "where table_schema = 'commuter_schema' and table_name = 'users' and column_name = 'id'")) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("bigint");
            }
            try (ResultSet rs = s.executeQuery("select to_regclass('commuter_schema.wallet_transactions')")) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("commuter_schema.wallet_transactions");
            }
        }
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}
