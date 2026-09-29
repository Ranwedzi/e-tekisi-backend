package com.etekisi.etekisi.integration;

import com.etekisi.etekisi.model.Role;
import com.etekisi.etekisi.model.User;
import com.etekisi.etekisi.model.WalletTransaction;
import com.etekisi.etekisi.repository.WalletTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import static com.etekisi.etekisi.integration.TestAuth.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Dev/test: sandbox provider on. */
@SpringBootTest
class WalletTopUpTest {
    @Autowired private WebApplicationContext context;
    @Autowired private Fixtures fixtures;
    @Autowired private WalletTransactionRepository ledger;
    private MockMvc mvc;

    @BeforeEach
    void setUp() { mvc = TestAuth.mvc(context); }

    @Test
    void topUpCreditsWalletAndWritesLedger() throws Exception {
        User u = fixtures.commuterWithBalance("0.00");
        mvc.perform(post("/api/wallet/" + u.getId() + "/topup").param("amount", "200")
                        .with(as(Role.COMMUTER, u.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(200.0));
        assertThat(ledger.findByOwnerIdAndOwnerRoleOrderByIdDesc(u.getId(), Role.COMMUTER))
                .singleElement()
                .satisfies(tx -> {
                    assertThat(tx.getType()).isEqualTo(WalletTransaction.Type.TOPUP);
                    assertThat(tx.getReference()).startsWith("SANDBOX-");
                });
    }

    @Test
    void topUpLimitsAreEnforced() throws Exception {
        User u = fixtures.commuterWithBalance("0.00");
        for (String bad : new String[]{"-100", "0", "5", "2000.01", "10.001"}) {
            mvc.perform(post("/api/wallet/" + u.getId() + "/topup").param("amount", bad)
                            .with(as(Role.COMMUTER, u.getId())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
        }
        assertThat(fixtures.walletBalance(u.getId())).isEqualByComparingTo("0");
    }
}

