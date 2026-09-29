package com.etekisi.etekisi.integration;

import com.etekisi.etekisi.model.*;
import com.etekisi.etekisi.repository.WalletTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;

import static com.etekisi.etekisi.integration.TestAuth.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The booking state machine and the money that moves with it. */
@SpringBootTest
class BookingMoneyTest {

    @Autowired private WebApplicationContext context;
    @Autowired private Fixtures fixtures;
    @Autowired private WalletTransactionRepository ledger;

    private MockMvc mvc;
    private User commuter;
    private Driver driver;
    private Driver otherDriver;

    @BeforeEach
    void setUp() {
        mvc = TestAuth.mvc(context);
        commuter = fixtures.commuterWithBalance("100.00");
        driver = fixtures.driver();
        otherDriver = fixtures.driver();
    }

    private void accept(Booking b, Driver d, String fare, int expectedStatus) throws Exception {
        mvc.perform(put("/api/bookings/" + b.getId() + "/accept/" + d.getId()).param("fare", fare)
                        .with(as(Role.DRIVER, d.getId())))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void acceptChargesFareAndShowsDriverDetails() throws Exception {
        Booking b = fixtures.pendingBooking(commuter.getId());
        mvc.perform(put("/api/bookings/" + b.getId() + "/accept/" + driver.getId()).param("fare", "35.50")
                        .with(as(Role.DRIVER, driver.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.fare").value(35.5))
                .andExpect(jsonPath("$.driverName").value("Test Driver"))
                .andExpect(jsonPath("$.taxiPlate").value("ABC123GP"))
                .andExpect(jsonPath("$.commuterPhone").value("0710000000"));
        assertThat(fixtures.walletBalance(commuter.getId())).isEqualByComparingTo("64.50");
    }

    @Test
    void secondAcceptIsRejectedAndChargesNothing() throws Exception {
        Booking b = fixtures.pendingBooking(commuter.getId());
        accept(b, driver, "30", 200);
        accept(b, otherDriver, "30", 409);
        assertThat(fixtures.walletBalance(commuter.getId())).isEqualByComparingTo("70.00");
    }

    @Test
    void invalidFaresAreRejected() throws Exception {
        Booking b = fixtures.pendingBooking(commuter.getId());
        accept(b, driver, "-50", 400);   // used to credit the commuter
        accept(b, driver, "0", 400);
        accept(b, driver, "10.123", 400);
        accept(b, driver, "5000", 400);  // above tekisi.booking.max-fare
        accept(b, driver, "abc", 400);
        assertThat(fixtures.status(b.getId())).isEqualTo(BookingStatus.PENDING);
        assertThat(fixtures.walletBalance(commuter.getId())).isEqualByComparingTo("100.00");
    }

    @Test
    void insufficientFundsLeavesBookingPendingForAnotherOffer() throws Exception {
        Booking b = fixtures.pendingBooking(commuter.getId());
        mvc.perform(put("/api/bookings/" + b.getId() + "/accept/" + driver.getId()).param("fare", "150")
                        .with(as(Role.DRIVER, driver.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.required").value(150.0))
                .andExpect(jsonPath("$.balance").value(100.0));
        assertThat(fixtures.status(b.getId())).isEqualTo(BookingStatus.PENDING);
        // A cheaper offer still works.
        accept(b, otherDriver, "40", 200);
        assertThat(fixtures.walletBalance(commuter.getId())).isEqualByComparingTo("60.00");
    }

    @Test
    void completeCreditsDriverExactlyOnceAndLedgerNetsToZero() throws Exception {
        Booking b = fixtures.pendingBooking(commuter.getId());
        accept(b, driver, "50", 200);

        // Someone else can't complete it.
        mvc.perform(put("/api/bookings/" + b.getId() + "/complete/" + otherDriver.getId())
                        .with(as(Role.DRIVER, otherDriver.getId())))
                .andExpect(status().isForbidden());

        mvc.perform(put("/api/bookings/" + b.getId() + "/complete/" + driver.getId())
                        .with(as(Role.DRIVER, driver.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.earned").value(50.0))
                .andExpect(jsonPath("$.booking.status").value("COMPLETED"));
        mvc.perform(put("/api/bookings/" + b.getId() + "/complete/" + driver.getId())
                        .with(as(Role.DRIVER, driver.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_COMPLETED"));

        assertThat(fixtures.driverBalance(driver.getId())).isEqualByComparingTo("50.00");
        assertThat(fixtures.walletBalance(commuter.getId())).isEqualByComparingTo("50.00");
        assertThat(ledgerSum(b)).isEqualByComparingTo("0");

        // A completed trip can't be cancelled for a refund.
        mvc.perform(put("/api/bookings/" + b.getId() + "/cancel").with(as(Role.DRIVER, driver.getId())))
                .andExpect(status().isConflict());
        assertThat(fixtures.walletBalance(commuter.getId())).isEqualByComparingTo("50.00");
    }

    @Test
    void commuterCancellingAcceptedTripIsRefunded() throws Exception {
        Booking b = fixtures.pendingBooking(commuter.getId());
        accept(b, driver, "45", 200);
        mvc.perform(put("/api/bookings/" + b.getId() + "/cancel").with(as(Role.COMMUTER, commuter.getId())))
                .andExpect(status().isOk());
        assertThat(fixtures.walletBalance(commuter.getId())).isEqualByComparingTo("100.00");
        assertThat(ledgerSum(b)).isEqualByComparingTo("0");
    }

    @Test
    void driverCancellingAcceptedTripRefundsCommuter() throws Exception {
        Booking b = fixtures.pendingBooking(commuter.getId());
        accept(b, driver, "45", 200);
        mvc.perform(put("/api/bookings/" + b.getId() + "/cancel").with(as(Role.DRIVER, driver.getId())))
                .andExpect(status().isOk());
        assertThat(fixtures.walletBalance(commuter.getId())).isEqualByComparingTo("100.00");
        mvc.perform(put("/api/bookings/" + b.getId() + "/cancel").with(as(Role.DRIVER, driver.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_CANCELLED"));
        assertThat(fixtures.walletBalance(commuter.getId())).isEqualByComparingTo("100.00");
    }

    @Test
    void unassignedDriverCannotCancelPendingBooking() throws Exception {
        Booking b = fixtures.pendingBooking(commuter.getId());
        mvc.perform(put("/api/bookings/" + b.getId() + "/cancel").with(as(Role.DRIVER, driver.getId())))
                .andExpect(status().isForbidden());
        assertThat(fixtures.status(b.getId())).isEqualTo(BookingStatus.PENDING);
    }

    @Test
    void declinedBookingsDisappearFromThatDriversPendingList() throws Exception {
        Booking b = fixtures.pendingBooking(commuter.getId());
        mvc.perform(post("/api/bookings/" + b.getId() + "/decline/" + driver.getId())
                        .with(as(Role.DRIVER, driver.getId())))
                .andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/bookings/pending")
                        .with(as(Role.DRIVER, driver.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + b.getId() + ")]").isEmpty());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/bookings/pending")
                        .with(as(Role.DRIVER, otherDriver.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + b.getId() + ")]").isNotEmpty());
    }

    @Test
    void payoutDebitsBalanceAndMasksAccount() throws Exception {
        Booking b = fixtures.pendingBooking(commuter.getId());
        accept(b, driver, "60", 200);
        mvc.perform(put("/api/bookings/" + b.getId() + "/complete/" + driver.getId())
                        .with(as(Role.DRIVER, driver.getId())))
                .andExpect(status().isOk());

        String path = "/api/payouts/driver/" + driver.getId() + "/request";
        mvc.perform(post(path).with(as(Role.DRIVER, driver.getId()))
                        .contentType("application/json")
                        .content("{\"amount\":100,\"method\":\"FNB_EWALLET\",\"phoneNumber\":\"0821234567\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
        mvc.perform(post(path).with(as(Role.DRIVER, driver.getId()))
                        .contentType("application/json")
                        .content("{\"amount\":-5,\"method\":\"FNB_EWALLET\",\"phoneNumber\":\"0821234567\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(path).with(as(Role.DRIVER, driver.getId()))
                        .contentType("application/json")
                        .content("""
                                {"amount":20,"method":"BANK_TRANSFER","bankName":"FNB",
                                 "accountNumber":"62123456789","accountHolder":"T Driver"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountNumberMasked").value("****6789"))
                .andExpect(jsonPath("$.accountNumber").doesNotExist());
        assertThat(fixtures.driverBalance(driver.getId())).isEqualByComparingTo("40.00");
    }

    private BigDecimal ledgerSum(Booking b) {
        return ledger.findByBookingId(b.getId()).stream()
                .map(WalletTransaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
