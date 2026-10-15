package com.etekisi.etekisi.integration;

import com.etekisi.etekisi.model.Booking;
import com.etekisi.etekisi.model.BookingStatus;
import com.etekisi.etekisi.model.Driver;
import com.etekisi.etekisi.model.User;
import com.etekisi.etekisi.service.BookingService;
import com.etekisi.etekisi.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Races on the same booking: exactly one winner, and the commuter is charged at most once. */
@SpringBootTest
class BookingConcurrencyTest {

    @Autowired private BookingService bookings;
    @Autowired private Fixtures fixtures;

    @Test
    void concurrentOffersHoldTheBookingForOneDriver() throws Exception {
        User commuter = fixtures.commuterWithBalance("100.00");
        Booking b = fixtures.pendingBooking(commuter.getId());
        List<Driver> racers = List.of(fixtures.driver(), fixtures.driver(), fixtures.driver(), fixtures.driver());

        List<String> outcomes = race(racers.size(), i -> bookings.offer(b.getId(), racers.get(i).getId(), new BigDecimal("30.00")));

        assertThat(outcomes).filteredOn("OK"::equals).hasSize(1);
        assertThat(outcomes).filteredOn("BOOKING_NOT_PENDING"::equals).hasSize(racers.size() - 1);
        assertThat(fixtures.status(b.getId())).isEqualTo(BookingStatus.OFFERED);
        assertThat(fixtures.walletBalance(commuter.getId())).isEqualByComparingTo("100.00");
    }

    @Test
    void doubleTappedAcceptChargesOnce() throws Exception {
        User commuter = fixtures.commuterWithBalance("100.00");
        Booking b = fixtures.pendingBooking(commuter.getId());
        bookings.offer(b.getId(), fixtures.driver().getId(), new BigDecimal("30.00"));

        List<String> outcomes = race(4, i -> bookings.acceptOffer(b.getId(), commuter.getId()));

        assertThat(outcomes).filteredOn("OK"::equals).hasSize(1);
        assertThat(outcomes).filteredOn("OFFER_NOT_AVAILABLE"::equals).hasSize(3);
        assertThat(fixtures.walletBalance(commuter.getId())).isEqualByComparingTo("70.00");
        assertThat(fixtures.status(b.getId())).isEqualTo(BookingStatus.ACCEPTED);
    }

    private interface Attempt { void run(int i); }

    private static List<String> race(int n, Attempt attempt) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            results.add(pool.submit(() -> {
                start.await();
                try {
                    attempt.run(idx);
                    return "OK";
                } catch (ApiException e) {
                    return e.getCode();
                }
            }));
        }
        start.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> f : results) outcomes.add(f.get(30, TimeUnit.SECONDS));
        pool.shutdown();
        return outcomes;
    }
}
