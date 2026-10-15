package com.etekisi.etekisi.integration;

import com.etekisi.etekisi.model.*;
import com.etekisi.etekisi.repository.BookingRepository;
import com.etekisi.etekisi.repository.DriverRepository;
import com.etekisi.etekisi.repository.WalletTransactionRepository;
import com.etekisi.etekisi.service.BookingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;

import static com.etekisi.etekisi.integration.TestAuth.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The booking state machine (offer -> accept -> complete / cancel) and the money that moves with it. */
@SpringBootTest
class BookingMoneyTest {

    @Autowired private WebApplicationContext context;
    @Autowired private Fixtures fixtures;
    @Autowired private WalletTransactionRepository ledger;
    @Autowired private BookingRepository bookings;
    @Autowired private DriverRepository drivers;
    @Autowired private BookingService bookingService;

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

    private void offer(Booking b, Driver d, String fare, int expectedStatus) throws Exception {
        mvc.perform(put("/api/bookings/" + b.getId() + "/offer").param("fare", fare)
                        .with(as(Role.DRIVER, d.getId())))
                .andExpect(status().is(expectedStatus));
    }

    private void acceptOffer(Booking b, int expectedStatus) throws Exception {
        mvc.perform(put("/api/bookings/" + b.getId() + "/accept-offer").with(as(Role.COMMUTER, commuter.getId())))
                .andExpect(status().is(expectedStatus));
    }

    /** Offer + accept: the usual way a trip starts. */
    private void startTrip(Booking b, Driver d, String fare) throws Exception {
        offer(b, d, fare, 200);
        acceptOffer(b, 200);
    }

    @Test
    void offerChargesNothingUntilTheCommuterAccepts() throws Exception {
        Booking b = fixtures.pendingBooking(commuter.getId());
        mvc.perform(put("/api/bookings/" + b.getId() + "/offer").param("fare", "35.50")
                        .with(as(Role.DRIVER, driver.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OFFERED"))
                .andExpect(jsonPath("$.fare").value(35.5))
                .andExpect(jsonPath("$.offerExpiresAt").isNotEmpty())
                // The commuter sees who is offering, but phone numbers wait until they accept.
                .andExpect(jsonPath("$.driverName").value("Test Driver"))
                .andExpect(jsonPath("$.taxiPlate").value("ABC123GP"))
                .andExpect(jsonPath("$.driverPhone").doesNotExist());
        assertThat(fixtures.walletBalance(commuter.getId())).isEqualByComparingTo("100.00");

        mvc.perform(put("/api/bookings/" + b.getId() + "/accept-offer").with(as(Role.COMMUTER, commuter.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.driverPhone").value("0820000000"))
                .andExpect(jsonPath("$.commuterPhone").value("0710000000"));
        assertThat(fixtures.walletBalance(commuter.getId())).isEqualByComparingTo("64.50");
    }

    @Test
    void onlyOneOfferAtATime() throws Exception {
        Booking b = fixtures.pendingBooking(commuter.getId());
        offer(b, driver, "30", 200);
        offer(b, otherDriver, "25", 409);
        // It's no longer in other drivers' open list either.
        mvc.perform(get("/api/bookings/pending").with(as(Role.DRIVER, otherDriver.getId())))
                .andExpect(jsonPath("$[?(@.id == " + b.getId() + ")]").isEmpty());
    }

    @Test
    void declinedOfferReopensTheRequest() throws Exception {
        Booking b = fixtures.pendingBooking(commuter.getId());
        offer(b, driver, "80", 200);
        mvc.perform(put("/api/bookings/" + b.getId() + "/decline-offer").with(as(Role.COMMUTER, commuter.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.driverId").doesNotExist());
        // Another driver can now make a cheaper offer.
        startTrip(b, otherDriver, "40");
        assertThat(fixtures.walletBalance(commuter.getId())).isEqualByComparingTo("60.00");
    }

    @Test
    void driverCanWithdrawTheirOffer() throws Exception {
        Booking b = fixtures.pendingBooking(commuter.getId());
        offer(b, driver, "30", 200);
        mvc.perform(put("/api/bookings/" + b.getId() + "/cancel").with(as(Role.DRIVER, driver.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        acceptOffer(b, 409);
    }

    @Test
    void expiredOfferCannotBeAccepted() throws Exception {
        Booking b = fixtures.pendingBooking(commuter.getId());
        offer(b, driver, "30", 200);
        Booking stored = bookings.findById(b.getId()).orElseThrow();
        stored.setOfferExpiresAt(Instant.now().minusSeconds(1));
        bookings.save(stored);

        mvc.perform(put("/api/bookings/" + b.getId() + "/accept-offer").with(as(Role.COMMUTER, commuter.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OFFER_NOT_AVAILABLE"));
        assertThat(fixtures.status(b.getId())).isEqualTo(BookingStatus.PENDING);
        assertThat(fixtures.walletBalance(commuter.getId())).isEqualByComparingTo("100.00");
    }

    @Test
    void expiryJobReopensLapsedOffers() throws Exception {
        Booking b = fixtures.pendingBooking(commuter.getId());
        offer(b, driver, "30", 200);
        Booking stored = bookings.findById(b.getId()).orElseThrow();
        stored.setOfferExpiresAt(Instant.now().minusSeconds(1));
        bookings.save(stored);

        bookingService.expireStaleOffers();

        Booking after = bookings.findById(b.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(after.getDriverId()).isNull();
        assertThat(after.getFare()).isNull();
    }

    @Test
    void offlineDriversCannotOffer() throws Exception {
        Driver offline = fixtures.driver();
        offline.setActive(false);
        drivers.save(offline);
        Booking b = fixtures.pendingBooking(commuter.getId());
        mvc.perform(put("/api/bookings/" + b.getId() + "/offer").param("fare", "30")
                        .with(as(Role.DRIVER, offline.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DRIVER_OFFLINE"));
    }

    @Test
    void invalidFaresAreRejected() throws Exception {
        Booking b = fixtures.pendingBooking(commuter.getId());
        offer(b, driver, "-50", 400);
        offer(b, driver, "0", 400);
        offer(b, driver, "10.123", 400);
        offer(b, driver, "5000", 400);  // above tekisi.booking.max-fare
        offer(b, driver, "abc", 400);
        assertThat(fixtures.status(b.getId())).isEqualTo(BookingStatus.PENDING);
    }

    @Test
    void insufficientFundsKeepsTheOfferOpenUntilTheCommuterTopsUp() throws Exception {
        Booking b = fixtures.pendingBooking(commuter.getId());
        offer(b, driver, "150", 200);
        mvc.perform(put("/api/bookings/" + b.getId() + "/accept-offer").with(as(Role.COMMUTER, commuter.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.required").value(150.0))
                .andExpect(jsonPath("$.balance").value(100.0));
        assertThat(fixtures.status(b.getId())).isEqualTo(BookingStatus.OFFERED);

        mvc.perform(post("/api/wallet/" + commuter.getId() + "/topup").param("amount", "50")
                        .with(as(Role.COMMUTER, commuter.getId())))
                .andExpect(status().isOk());
        acceptOffer(b, 200);
        assertThat(fixtures.walletBalance(commuter.getId())).isEqualByComparingTo("0.00");
    }

    @Test
    void onlyTheBookingsCommuterCanAcceptOrDecline() throws Exception {
        User stranger = fixtures.commuterWithBalance("500.00");
        Booking b = fixtures.pendingBooking(commuter.getId());
        offer(b, driver, "30", 200);
        mvc.perform(put("/api/bookings/" + b.getId() + "/accept-offer").with(as(Role.COMMUTER, stranger.getId())))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/bookings/" + b.getId() + "/decline-offer").with(as(Role.COMMUTER, stranger.getId())))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/bookings/" + b.getId() + "/accept-offer").with(as(Role.DRIVER, driver.getId())))
                .andExpect(status().isForbidden());
        assertThat(fixtures.walletBalance(stranger.getId())).isEqualByComparingTo("500.00");
    }

    @Test
    void completeCreditsDriverExactlyOnceAndLedgerNetsToZero() throws Exception {
        Booking b = fixtures.pendingBooking(commuter.getId());
        startTrip(b, driver, "50");

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
    void completingAnOfferThatWasNeverAcceptedIsRejected() throws Exception {
        Booking b = fixtures.pendingBooking(commuter.getId());
        offer(b, driver, "50", 200);
        mvc.perform(put("/api/bookings/" + b.getId() + "/complete/" + driver.getId())
                        .with(as(Role.DRIVER, driver.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOKING_NOT_ACCEPTED"));
        assertThat(fixtures.driverBalance(driver.getId())).isEqualByComparingTo("0");
    }

    @Test
    void commuterCancellingAcceptedTripIsRefunded() throws Exception {
        Booking b = fixtures.pendingBooking(commuter.getId());
        startTrip(b, driver, "45");
        mvc.perform(put("/api/bookings/" + b.getId() + "/cancel").with(as(Role.COMMUTER, commuter.getId())))
                .andExpect(status().isOk());
        assertThat(fixtures.walletBalance(commuter.getId())).isEqualByComparingTo("100.00");
        assertThat(ledgerSum(b)).isEqualByComparingTo("0");
    }

    @Test
    void driverCancellingAcceptedTripRefundsCommuter() throws Exception {
        Booking b = fixtures.pendingBooking(commuter.getId());
        startTrip(b, driver, "45");
        mvc.perform(put("/api/bookings/" + b.getId() + "/cancel").with(as(Role.DRIVER, driver.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
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
        mvc.perform(get("/api/bookings/pending").with(as(Role.DRIVER, driver.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + b.getId() + ")]").isEmpty());
        mvc.perform(get("/api/bookings/pending").with(as(Role.DRIVER, otherDriver.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + b.getId() + ")]").isNotEmpty());
    }

    @Test
    void payoutDebitsBalanceAndMasksAccount() throws Exception {
        Booking b = fixtures.pendingBooking(commuter.getId());
        startTrip(b, driver, "60");
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
