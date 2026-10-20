package com.etekisi.etekisi.integration;

import com.etekisi.etekisi.model.*;
import com.etekisi.etekisi.repository.DriverRepository;
import com.etekisi.etekisi.repository.WalletRepository;
import com.etekisi.etekisi.service.ReconciliationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;

import static com.etekisi.etekisi.integration.TestAuth.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real money flows through the API, then reconciliation. Its own empty
 * database, so the totals are exactly what this test moved.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:tekisi_recon;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD) // a fresh, empty database per test
class ReconciliationTest {

    private static final long OPERATOR = 9600L;

    @Autowired private WebApplicationContext context;
    @Autowired private Fixtures fixtures;
    @Autowired private ReconciliationService reconciliation;
    @Autowired private WalletRepository wallets;
    @Autowired private DriverRepository drivers;
    @Autowired private JdbcTemplate jdbc;

    private void trip(MockMvc mvc, User c, Driver d, String fare, boolean complete) throws Exception {
        Booking b = fixtures.pendingBooking(c.getId());
        mvc.perform(put("/api/bookings/" + b.getId() + "/offer").param("fare", fare).with(as(Role.DRIVER, d.getId())))
                .andExpect(status().isOk());
        mvc.perform(put("/api/bookings/" + b.getId() + "/accept-offer").with(as(Role.COMMUTER, c.getId())))
                .andExpect(status().isOk());
        if (complete) {
            mvc.perform(put("/api/bookings/" + b.getId() + "/complete/" + d.getId()).with(as(Role.DRIVER, d.getId())))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void everyRandIsAccountedForThenTamperingIsCaught() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        User c = fixtures.commuter();
        Driver d = fixtures.driver();
        mvc.perform(post("/api/wallet/" + c.getId() + "/topup").param("amount", "200").with(as(Role.COMMUTER, c.getId())))
                .andExpect(status().isOk());

        trip(mvc, c, d, "40", true);   // completed: R40 commuter -> driver
        trip(mvc, c, d, "25", false);  // in progress: R25 charged, not yet earned
        Booking cancelled = fixtures.pendingBooking(c.getId());
        mvc.perform(put("/api/bookings/" + cancelled.getId() + "/offer").param("fare", "30").with(as(Role.DRIVER, d.getId())));
        mvc.perform(put("/api/bookings/" + cancelled.getId() + "/accept-offer").with(as(Role.COMMUTER, c.getId())));
        mvc.perform(put("/api/bookings/" + cancelled.getId() + "/cancel").with(as(Role.COMMUTER, c.getId())))
                .andExpect(status().isOk()); // refunded
        mvc.perform(post("/api/payouts/driver/" + d.getId() + "/request").with(as(Role.DRIVER, d.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":30,\"method\":\"FNB_EWALLET\",\"phoneNumber\":\"0821234567\"}"))
                .andExpect(status().isOk());

        String body = mvc.perform(post("/api/ops/reconciliation/run").with(as(Role.OPERATOR, OPERATOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run.status").value("OK"))
                .andExpect(jsonPath("$.findings.length()").value(0))
                .andReturn().getResponse().getContentAsString();
        var run = new ObjectMapper().readTree(body).get("run");
        // 200 in = 135 wallet + 10 driver + 25 in flight + 30 paid out
        assertThat(run.get("topups").decimalValue()).isEqualByComparingTo("200");
        assertThat(run.get("walletBalances").decimalValue()).isEqualByComparingTo("135");
        assertThat(run.get("driverBalances").decimalValue()).isEqualByComparingTo("10");
        assertThat(run.get("inFlight").decimalValue()).isEqualByComparingTo("25");
        assertThat(run.get("paidOut").decimalValue()).isEqualByComparingTo("30");
        assertThat(run.get("triggeredBy").asText()).isEqualTo("MANUAL:" + OPERATOR);

        // Someone edits a balance directly in the database.
        Wallet w = wallets.findByUserIdAndRole(c.getId(), Role.COMMUTER).orElseThrow();
        w.setBalance(new BigDecimal("1135.00"));
        wallets.save(w);
        // ...and a ledger entry is altered.
        jdbc.update("UPDATE commuter_schema.wallet_transactions SET amount = 45 WHERE type = 'TRIP_EARNING'");

        ReconciliationService.Report r = reconciliation.run("TEST");
        assertThat(r.run().getStatus()).isEqualTo(ReconciliationRun.Status.DISCREPANCIES);
        assertThat(r.findings()).extracting(ReconciliationFinding::getCode)
                .contains("BALANCE_MISMATCH", "CONSERVATION_MISMATCH", "LEDGER_CHAIN_BROKEN", "TRIP_MONEY_MISMATCH");
        assertThat(r.findings()).filteredOn(f -> f.getCode().equals("BALANCE_MISMATCH"))
                .anySatisfy(f -> {
                    assertThat(f.getOwnerId()).isEqualTo(c.getId());
                    assertThat(f.getActual()).isEqualByComparingTo("1135.00");
                    assertThat(f.getExpected()).isEqualByComparingTo("135.00");
                });

        mvc.perform(get("/api/ops/reconciliation").with(as(Role.OPERATOR, OPERATOR)))
                .andExpect(jsonPath("$.run.status").value("DISCREPANCIES"))
                .andExpect(jsonPath("$.findings[0].severity").value("CRITICAL"));
        mvc.perform(get("/api/ops/reconciliation").with(as(Role.DRIVER, d.getId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void aBalanceFromBeforeTheLedgerIsAWarningNotAnError() {
        Driver d = fixtures.driver();
        // An opening balance written as the first ledger entry (as an import would).
        jdbc.update("INSERT INTO commuter_schema.wallet_transactions (owner_id, owner_role, type, amount, balance_after, created_at) "
                + "VALUES (?, 'DRIVER', 'TRIP_EARNING', 0, 70, CURRENT_TIMESTAMP)", d.getId());
        // (TRIP_EARNING without a trip is also an orphan; that part is a real error.)
        d.setBalance(new BigDecimal("70.00"));
        drivers.save(d);
        ReconciliationService.Report r = reconciliation.run("TEST");
        assertThat(r.findings()).anySatisfy(f -> {
            assertThat(f.getCode()).isEqualTo("OPENING_BALANCE");
            assertThat(f.getSeverity()).isEqualTo(ReconciliationFinding.Severity.WARNING);
        });
        assertThat(r.run().getOpeningBalances()).isEqualByComparingTo("70");
    }
}
