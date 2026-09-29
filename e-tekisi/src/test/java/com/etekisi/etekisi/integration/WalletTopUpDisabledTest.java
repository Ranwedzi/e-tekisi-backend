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

/** Prod: no sandbox and no real provider, so top-up is refused. */
@SpringBootTest(properties = "tekisi.payments.sandbox-enabled=false")
class WalletTopUpDisabledTest {
    @Autowired private WebApplicationContext context;
    @Autowired private Fixtures fixtures;

    @Test
    void topUpIsRefused() throws Exception {
        User u = fixtures.commuterWithBalance("0.00");
        TestAuth.mvc(context).perform(post("/api/wallet/" + u.getId() + "/topup").param("amount", "100")
                        .with(as(Role.COMMUTER, u.getId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TOPUP_DISABLED"));
        assertThat(fixtures.walletBalance(u.getId())).isEqualByComparingTo("0");
    }
}
