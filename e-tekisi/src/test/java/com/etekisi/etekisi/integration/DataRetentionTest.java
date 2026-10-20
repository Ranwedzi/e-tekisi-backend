package com.etekisi.etekisi.integration;

import com.etekisi.etekisi.agent.insight.InsightCode;
import com.etekisi.etekisi.agent.insight.OperationalInsight;
import com.etekisi.etekisi.agent.insight.OperationalInsightRepository;
import com.etekisi.etekisi.agent.trip.TripGpsPing;
import com.etekisi.etekisi.agent.trip.TripGpsPingRepository;
import com.etekisi.etekisi.model.*;
import com.etekisi.etekisi.repository.DriverLocationRepository;
import com.etekisi.etekisi.repository.MessageRepository;
import com.etekisi.etekisi.repository.NotificationRepository;
import com.etekisi.etekisi.service.DataRetentionJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Personal data is purged once it's past its retention period; safety evidence is kept. */
@SpringBootTest
class DataRetentionTest {

    @Autowired private TripGpsPingRepository pings;
    @Autowired private DriverLocationRepository locations;
    @Autowired private MessageRepository messages;
    @Autowired private NotificationRepository notifications;
    @Autowired private OperationalInsightRepository insights;
    @Autowired private TransactionTemplate tx;
    @Autowired private Fixtures fixtures;

    private DataRetentionJob job(int gps, int location, int chat, int notes) {
        return new DataRetentionJob(pings, locations, messages, notifications, tx, gps, location, chat, notes);
    }

    private TripGpsPing ping(long bookingId, Instant at) {
        TripGpsPing p = new TripGpsPing();
        p.setBookingId(bookingId);
        p.setLat(-26.2);
        p.setLng(28.04);
        p.setRecordedAt(at);
        p.setSource("DRIVER_APP");
        return pings.save(p);
    }

    private Message message(long bookingId, LocalDateTime at) {
        Message m = new Message();
        m.setBookingId(bookingId);
        m.setSenderId(1L);
        m.setSenderRole(Role.COMMUTER);
        m.setContent("hi");
        m.setTimestamp(at);
        return messages.save(m);
    }

    @Test
    void purgesOldRowsAndKeepsRecentOnesAndFlaggedTrips() {
        Instant old = Instant.now().minus(Duration.ofDays(120));
        long plainTrip = 880_001L;
        long panicTrip = 880_002L;
        TripGpsPing oldPlain = ping(plainTrip, old);
        TripGpsPing recentPlain = ping(plainTrip, Instant.now());
        TripGpsPing oldPanic = ping(panicTrip, old);
        OperationalInsight panic = new OperationalInsight();
        panic.setBookingId(panicTrip);
        panic.setType(OperationalInsight.Type.ALERT);
        panic.setCode(InsightCode.SAFETY_PANIC);
        panic.setSeverity(OperationalInsight.Severity.CRITICAL);
        panic.setMessage("Panic button pressed");
        panic.setCreatedAt(old);
        insights.save(panic);

        Driver stale = fixtures.driver();
        Driver fresh = fixtures.driver();
        for (Driver d : new Driver[]{stale, fresh}) {
            DriverLocation l = new DriverLocation();
            l.setDriverId(d.getId());
            l.setLat(-26.2);
            l.setLng(28.04);
            l.setLastUpdated(d == stale ? LocalDateTime.now().minusDays(10) : LocalDateTime.now());
            locations.save(l);
        }

        Message oldChat = message(plainTrip, LocalDateTime.now().minusDays(200));
        Message newChat = message(plainTrip, LocalDateTime.now());

        DashboardNotification oldNote = new DashboardNotification();
        oldNote.setUserId(1L);
        oldNote.setRole(Role.COMMUTER);
        oldNote.setMessage("old");
        oldNote.setCreatedAt(LocalDateTime.now().minusDays(100));
        oldNote = notifications.save(oldNote);

        job(90, 7, 180, 90).purge();

        assertThat(pings.findById(oldPlain.getId())).isEmpty();
        assertThat(pings.findById(recentPlain.getId())).isPresent();
        assertThat(pings.findById(oldPanic.getId())).as("panic trip GPS kept as evidence").isPresent();
        assertThat(locations.findById(stale.getId())).isEmpty();
        assertThat(locations.findById(fresh.getId())).isPresent();
        assertThat(messages.findById(oldChat.getId())).isEmpty();
        assertThat(messages.findById(newChat.getId())).isPresent();
        assertThat(notifications.findById(oldNote.getId())).isEmpty();
    }

    @Test
    void zeroDaysTurnsAPurgeOff() {
        Message ancient = message(880_003L, LocalDateTime.now().minusDays(1000));
        job(0, 0, 0, 0).purge();
        assertThat(messages.findById(ancient.getId())).isPresent();
    }
}
