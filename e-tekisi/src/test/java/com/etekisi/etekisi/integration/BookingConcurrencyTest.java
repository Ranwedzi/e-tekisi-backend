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

/** Two drivers racing to accept the same booking: exactly one wins, the commuter is charged once. */
@SpringBootTest
class BookingConcurrencyTest {

    @Autowired private BookingService bookings;
    @Autowired private Fixtures fixtures;

    @Test
    void concurrentAcceptsChargeOnce() throws Exception {
        User commuter = fixtures.commuterWithBalance("100.00");
        Booking b = fixtures.pendingBooking(commuter.getId());
        List<Driver> racers = List.of(fixtures.driver(), fixtures.driver(), fixtures.driver(), fixtures.driver());

        ExecutorService pool = Executors.newFixedThreadPool(racers.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (Driver d : racers) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    bookings.accept(b.getId(), d.getId(), new BigDecimal("30.00"));
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

        assertThat(outcomes).filteredOn("OK"::equals).hasSize(1);
        assertThat(outcomes).filteredOn("BOOKING_NOT_PENDING"::equals).hasSize(racers.size() - 1);
        assertThat(fixtures.walletBalance(commuter.getId())).isEqualByComparingTo("70.00");
        assertThat(fixtures.status(b.getId())).isEqualTo(BookingStatus.ACCEPTED);
    }
}
