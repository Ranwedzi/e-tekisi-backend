package com.etekisi.etekisi.integration;

import com.etekisi.etekisi.model.Driver;
import com.etekisi.etekisi.model.Role;
import com.etekisi.etekisi.model.WalletTransaction;
import com.etekisi.etekisi.repository.DriverRepository;
import com.etekisi.etekisi.repository.WalletTransactionRepository;
import com.etekisi.etekisi.security.LegacyPayoutEncryption;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;

import static com.etekisi.etekisi.integration.TestAuth.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Payout approval workflow and encryption of bank details at rest. */
@SpringBootTest
class PayoutWorkflowTest {

    private static final long OPERATOR = 9001L;

    @Autowired private WebApplicationContext context;
    @Autowired private Fixtures fixtures;
    @Autowired private DriverRepository drivers;
    @Autowired private WalletTransactionRepository ledger;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LegacyPayoutEncryption legacyEncryption;

    private MockMvc mvc;
    private Driver driver;

    @BeforeEach
    void setUp() {
        mvc = TestAuth.mvc(context);
        driver = fixtures.driver();
        driver.setBalance(new BigDecimal("200.00"));
        drivers.save(driver);
    }

    private long requestBankPayout(String amount) throws Exception {
        String body = mvc.perform(post("/api/payouts/driver/" + driver.getId() + "/request")
                        .with(as(Role.DRIVER, driver.getId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":%s,"method":"BANK_TRANSFER","bankName":"FNB",
                                 "accountNumber":"62123456789","accountHolder":"T Driver"}""".formatted(amount)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    @Test
    void approveThenMarkPaid() throws Exception {
        long id = requestBankPayout("50");
        assertThat(fixtures.driverBalance(driver.getId())).isEqualByComparingTo("150.00");

        // Operators see the full details they need to pay it.
        mvc.perform(get("/api/payouts").param("status", "PENDING").with(as(Role.OPERATOR, OPERATOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + id + ")].accountNumber").value("62123456789"))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].driverName").value("Test Driver"));

        // Can't be marked paid before it's approved.
        mvc.perform(put("/api/payouts/" + id + "/paid").with(as(Role.OPERATOR, OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reference\":\"EFT-1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYOUT_WRONG_STATUS"));

        mvc.perform(put("/api/payouts/" + id + "/approve").with(as(Role.OPERATOR, OPERATOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.processedBy").value(OPERATOR));
        mvc.perform(put("/api/payouts/" + id + "/paid").with(as(Role.OPERATOR, OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reference\":\"EFT-12345\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.reference").value("EFT-12345"));

        // Paid is final.
        mvc.perform(put("/api/payouts/" + id + "/reject").with(as(Role.OPERATOR, OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"oops\"}"))
                .andExpect(status().isConflict());
        assertThat(fixtures.driverBalance(driver.getId())).isEqualByComparingTo("150.00");

        // The driver sees the outcome, still masked.
        mvc.perform(get("/api/payouts/driver/" + driver.getId()).with(as(Role.DRIVER, driver.getId())))
                .andExpect(jsonPath("$[0].status").value("PAID"))
                .andExpect(jsonPath("$[0].accountNumberMasked").value("****6789"))
                .andExpect(jsonPath("$[0].accountNumber").doesNotExist());
    }

    @Test
    void rejectRefundsTheDriver() throws Exception {
        long id = requestBankPayout("80");
        assertThat(fixtures.driverBalance(driver.getId())).isEqualByComparingTo("120.00");

        mvc.perform(put("/api/payouts/" + id + "/reject").with(as(Role.ADMIN, OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Account name doesn't match\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value("Account name doesn't match"));

        assertThat(fixtures.driverBalance(driver.getId())).isEqualByComparingTo("200.00");
        BigDecimal net = ledger.findByOwnerIdAndOwnerRoleOrderByIdDesc(driver.getId(), Role.DRIVER).stream()
                .filter(t -> t.getType() == WalletTransaction.Type.PAYOUT || t.getType() == WalletTransaction.Type.PAYOUT_REVERSAL)
                .map(WalletTransaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(net).isEqualByComparingTo("0");

        // Rejecting twice must not refund twice.
        mvc.perform(put("/api/payouts/" + id + "/reject").with(as(Role.ADMIN, OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"again\"}"))
                .andExpect(status().isConflict());
        assertThat(fixtures.driverBalance(driver.getId())).isEqualByComparingTo("200.00");
    }

    @Test
    void driversAndCommutersCannotUseOperatorEndpoints() throws Exception {
        long id = requestBankPayout("20");
        mvc.perform(get("/api/payouts").with(as(Role.DRIVER, driver.getId())))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/payouts/" + id + "/approve").with(as(Role.DRIVER, driver.getId())))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/payouts/" + id + "/reject").with(as(Role.COMMUTER, 1L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/payouts/" + id + "/reject").with(as(Role.OPERATOR, OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void bankDetailsAreEncryptedInTheDatabase() throws Exception {
        long id = requestBankPayout("20");
        var row = jdbc.queryForMap(
                "select account_number, account_holder, bank_name from driver_schema.payout_requests where id = ?", id);
        assertThat((String) row.get("account_number")).startsWith("enc:v1:").doesNotContain("62123456789");
        assertThat((String) row.get("account_holder")).startsWith("enc:v1:");
        assertThat(row.get("bank_name")).isEqualTo("FNB"); // not sensitive, left readable for reporting
    }

    @Test
    void legacyPlaintextRowsAreEncryptedAtStartupAndStillReadable() throws Exception {
        long id = requestBankPayout("20");
        // Simulate a row written before encryption existed.
        jdbc.update("update driver_schema.payout_requests set account_number = '99887766554' where id = ?", id);

        legacyEncryption.run(null);

        String stored = jdbc.queryForObject(
                "select account_number from driver_schema.payout_requests where id = ?", String.class, id);
        assertThat(stored).startsWith("enc:v1:");
        mvc.perform(get("/api/payouts").with(as(Role.OPERATOR, OPERATOR)))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].accountNumber").value("99887766554"));
    }
}
