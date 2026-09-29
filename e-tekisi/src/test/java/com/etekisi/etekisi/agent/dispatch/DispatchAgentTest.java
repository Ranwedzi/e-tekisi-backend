package com.etekisi.etekisi.agent.dispatch;

import com.etekisi.etekisi.agent.audit.DecisionAuditLogger;
import com.etekisi.etekisi.agent.dto.DispatchDecision;
import com.etekisi.etekisi.agent.guardrail.*;
import com.etekisi.etekisi.model.Booking;
import com.etekisi.etekisi.model.BookingStatus;
import com.etekisi.etekisi.model.DriverLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;

class DispatchAgentTest {

    private TrustedDataService trusted;
    private DecisionAuditLogger audit;
    private AgentProperties props;
    private DispatchAgent agent;

    @BeforeEach
    void setUp() {
        trusted = Mockito.mock(TrustedDataService.class);
        audit = Mockito.mock(DecisionAuditLogger.class);
        props = new AgentProperties();
        agent = new DispatchAgent(trusted, props, new DecisionGate(), new PolicyEngine(), audit);

        // Default safe stubs.
        Mockito.when(trusted.countRecentDeclinesFor(anyCollection()))
                .thenReturn(TrustedFact.of(Map.of(), "BookingDeclineRepository"));
        Mockito.when(trusted.getQueueAtRank(anyString()))
                .thenReturn(TrustedFact.of(List.of(), "QueueRepository"));
    }

    @Test
    void rejectsWhenBookingMissing() {
        Mockito.when(trusted.getBooking(99L)).thenReturn(TrustedFact.missing("BookingRepository"));
        DispatchDecision d = agent.dispatch(99L, -26.2, 28.0, "corr");
        assertThat(d.status()).isEqualTo(DispatchDecision.Status.REJECTED);
        assertThat(d.assignedDriverId()).isNull();
        assertThat(d.metadata().fallbackUsed()).isTrue();
    }

    @Test
    void degradedWhenCommuterGpsMissing() {
        Mockito.when(trusted.getBooking(1L)).thenReturn(TrustedFact.of(booking(1L), "BookingRepository"));
        DispatchDecision d = agent.dispatch(1L, null, null, "corr");
        assertThat(d.status()).isEqualTo(DispatchDecision.Status.DEGRADED_NO_MATCH);
        assertThat(d.assignedDriverId()).isNull();
        assertThat(d.metadata().degraded()).isTrue();
    }

    @Test
    void noMatchWhenNoActiveDrivers() {
        Mockito.when(trusted.getBooking(1L)).thenReturn(TrustedFact.of(booking(1L), "BookingRepository"));
        Mockito.when(trusted.getActiveDriverLocations(any(Duration.class)))
                .thenReturn(TrustedFact.of(List.of(), "DriverLocationRepository"));
        DispatchDecision d = agent.dispatch(1L, -26.2, 28.0, "corr");
        assertThat(d.status()).isEqualTo(DispatchDecision.Status.NO_MATCH);
        assertThat(d.metadata().fallbackUsed()).isTrue();
    }

    @Test
    void neverAssignsAStaleDriverAutonomously() {
        Mockito.when(trusted.getBooking(1L)).thenReturn(TrustedFact.of(booking(1L), "BookingRepository"));
        DriverLocation loc = driverLoc(7L, -26.2005, 28.0475); // very close
        Mockito.when(trusted.getActiveDriverLocations(any(Duration.class)))
                .thenReturn(TrustedFact.of(List.of(loc), "DriverLocationRepository"));
        // Critically: no fresh location => agent must mark degraded => no autonomous assignment.
        Mockito.when(trusted.getFreshDriverLocation(7L))
                .thenReturn(TrustedFact.missing("DriverLocationRepository(stale)"));

        DispatchDecision d = agent.dispatch(1L, -26.2008, 28.0473, "corr");

        assertThat(d.status()).isEqualTo(DispatchDecision.Status.DEGRADED_NO_MATCH);
        assertThat(d.assignedDriverId()).isNull();
        assertThat(d.metadata().degraded()).isTrue();
    }

    @Test
    void assignsClosestFreshDriverWhenAllChecksPass() {
        Mockito.when(trusted.getBooking(1L)).thenReturn(TrustedFact.of(booking(1L), "BookingRepository"));
        DriverLocation close = driverLoc(7L, -26.2010, 28.0475);
        DriverLocation farther = driverLoc(8L, -26.2200, 28.0600);
        Mockito.when(trusted.getActiveDriverLocations(any(Duration.class)))
                .thenReturn(TrustedFact.of(List.of(close, farther), "DriverLocationRepository"));
        Mockito.when(trusted.getFreshDriverLocation(7L))
                .thenReturn(TrustedFact.of(close, "DriverLocationRepository"));
        Mockito.when(trusted.getFreshDriverLocation(8L))
                .thenReturn(TrustedFact.of(farther, "DriverLocationRepository"));

        DispatchDecision d = agent.dispatch(1L, -26.2008, 28.0473, "corr");

        assertThat(d.status()).isEqualTo(DispatchDecision.Status.ASSIGNED);
        assertThat(d.assignedDriverId()).isEqualTo(7L);
        assertThat(d.metadata().fallbackUsed()).isFalse();
        assertThat(d.metadata().degraded()).isFalse();
    }

    private Booking booking(Long id) {
        Booking b = new Booking();
        b.setId(id);
        b.setCommuterId(100L);
        b.setStatus(BookingStatus.PENDING);
        b.setPickupLocation("Park Station");
        b.setDestination("Baragwanath");
        return b;
    }

    private DriverLocation driverLoc(Long driverId, double lat, double lng) {
        DriverLocation l = new DriverLocation();
        l.setDriverId(driverId);
        l.setLat(lat);
        l.setLng(lng);
        l.setLastUpdated(LocalDateTime.now());
        return l;
    }
}
