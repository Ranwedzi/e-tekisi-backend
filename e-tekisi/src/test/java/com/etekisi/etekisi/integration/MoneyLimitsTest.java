package com.etekisi.etekisi.integration;

import com.etekisi.etekisi.model.Driver;
import com.etekisi.etekisi.model.Role;
import com.etekisi.etekisi.model.User;
import com.etekisi.etekisi.model.Wallet;
import com.etekisi.etekisi.repository.DriverRepository;
import com.etekisi.etekisi.repository.WalletRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;

import static com.etekisi.etekisi.integration.TestAuth.as;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Hard limits on top-ups and payouts, and payouts held by the risk check. */
@SpringBootTest
class MoneyLimitsTest {

    private static final long OPERATOR = 9700L;

    @Autowired private WebApplicationContext context;
    @Autowired private Fixtures fixtures;
    @Autowired private WalletRepository wallets;
    @Autowired private DriverRepository drivers;

    private ResultActions topUp(MockMvc mvc, User c, String amount) throws Exception {
        return mvc.perform(post("/api/wallet/" + c.getId() + "/topup").param("amount", amount).with(as(Role.COMMUTER, c.getId())));
    }

    private ResultActions payout(MockMvc mvc, Driver d, String amount) throws Exception {
        return mvc.perform(post("/api/payouts/driver/" + d.getId() + "/request").with(as(Role.DRIVER, d.getId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":" + amount + ",\"method\":\"FNB_EWALLET\",\"phoneNumber\":\"0821234567\"}"));
    }

    private Driver driverWith(String balance, int trips) {
        Driver d = fixtures.driver();
        d.setBalance(new BigDecimal(balance));
        d.setTripsCompleted(trips);
        return drivers.save(d);
    }

    @Test
    void newAccountsHaveALowerDailyTopUpLimit() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        User c = fixtures.commuter(); // no trips yet: R500 a day
        topUp(mvc, c, "300").andExpect(status().isOk());
        topUp(mvc, c, "300")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LIMIT_REACHED"))
                .andExpect(jsonPath("$.limit").value("TOPUP_DAILY_LIMIT"))
                .andExpect(jsonPath("$.remaining").value(200.0))
                .andExpect(jsonPath("$.resetsAt").isNotEmpty());
        topUp(mvc, c, "200").andExpect(status().isOk());
        mvc.perform(get("/api/wallet/" + c.getId() + "/limits").with(as(Role.COMMUTER, c.getId())))
                .andExpect(jsonPath("$.topUpToday").value(0.0))
                .andExpect(jsonPath("$.topUpsLeftToday").value(3))
                .andExpect(jsonPath("$.dailyTopUpLimit").value(500.0));
        // The wallet balance is what was actually charged.
        org.assertj.core.api.Assertions.assertThat(fixtures.walletBalance(c.getId())).isEqualByComparingTo("500.00");
    }

    @Test
    void topUpsAreLimitedInNumberAndByTheWalletMaximum() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        User c = fixtures.commuter();
        for (int i = 0; i < 5; i++) topUp(mvc, c, "10").andExpect(status().isOk());
        topUp(mvc, c, "10").andExpect(jsonPath("$.limit").value("TOPUP_DAILY_COUNT"));

        User regular = fixtures.commuterWithBalance("2900");
        Wallet w = wallets.findByUserIdAndRole(regular.getId(), Role.COMMUTER).orElseThrow();
        w.setTotalTrips(10);
        wallets.save(w);
        topUp(mvc, regular, "200")
                .andExpect(jsonPath("$.limit").value("WALLET_BALANCE_LIMIT"))
                .andExpect(jsonPath("$.remaining").value(100.0));
        topUp(mvc, regular, "100").andExpect(status().isOk());
    }

    @Test
    void payoutsHaveADailyLimitAndRejectedOnesDontUseItUp() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        Driver d = driverWith("8000", 20);
        String first = payout(mvc, d, "3000").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        payout(mvc, d, "2500")
                .andExpect(jsonPath("$.limit").value("PAYOUT_DAILY_LIMIT"))
                .andExpect(jsonPath("$.remaining").value(2000.0));
        long id = Long.parseLong(first.replaceAll(".*\"id\":(\\d+).*", "$1"));
        mvc.perform(put("/api/payouts/" + id + "/reject").with(as(Role.OPERATOR, OPERATOR))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Wrong account\"}"))
                .andExpect(status().isOk());
        payout(mvc, d, "2500").andExpect(status().isOk());
        payout(mvc, d, "20").andExpect(status().isOk()); // third request (refused ones aren't created)
        payout(mvc, d, "20")
                .andExpect(jsonPath("$.limit").value("PAYOUT_DAILY_COUNT"));
        mvc.perform(get("/api/payouts/driver/" + d.getId() + "/limits").with(as(Role.DRIVER, d.getId())))
                .andExpect(jsonPath("$.payoutsLeftToday").value(0))
                .andExpect(jsonPath("$.payoutToday").value(2480.0));
    }

    @Test
    void flaggedPayoutsNeedTheOperatorToConfirmTheyChecked() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        Driver newDriver = driverWith("2000", 0);   // large amount + new driver: held
        String body = payout(mvc, newDriver, "1600").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long held = Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));

        mvc.perform(get("/api/payouts").param("status", "PENDING").with(as(Role.OPERATOR, OPERATOR)))
                .andExpect(jsonPath("$[?(@.id == " + held + ")].riskHold").value(true))
                .andExpect(jsonPath("$[?(@.id == " + held + ")].riskReasons")
                        .value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("amount high"))));
        mvc.perform(put("/api/payouts/" + held + "/approve").with(as(Role.OPERATOR, OPERATOR)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RISK_REVIEW_REQUIRED"));
        mvc.perform(put("/api/payouts/" + held + "/approve").with(as(Role.OPERATOR, OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"acknowledgeRisk\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.riskClearedBy").value(OPERATOR));

        Driver regular = driverWith("500", 30);     // ordinary payout: approved as before
        String ok = payout(mvc, regular, "100").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long normal = Long.parseLong(ok.replaceAll(".*\"id\":(\\d+).*", "$1"));
        mvc.perform(put("/api/payouts/" + normal + "/approve").with(as(Role.OPERATOR, OPERATOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskHold").value(false));
    }
}
