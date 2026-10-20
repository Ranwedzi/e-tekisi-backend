package com.etekisi.etekisi.realtime;

import com.etekisi.etekisi.agent.insight.OperationalInsight;
import com.etekisi.etekisi.model.*;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * JPA entity listeners that turn data changes into live events, so every code
 * path that changes a booking, message, notification, queue ticket, payout,
 * alert or driver document is covered without each one remembering to.
 * (Spring creates these, so they can be injected.)
 */
public final class LiveEntityListeners {

    private LiveEntityListeners() {}

    @Component
    public static class Bookings {
        private final ObjectProvider<LiveEvents> events;

        public Bookings(ObjectProvider<LiveEvents> events) {
            this.events = events;
        }

        @PostPersist
        @PostUpdate
        void changed(Booking b) {
            LiveEvents e = events.getIfAvailable();
            if (e == null) return;
            e.toUser(Role.COMMUTER, b.getCommuterId(), "bookings");
            e.toUser(Role.DRIVER, b.getDriverId(), "bookings");
            // Open requests appear, are taken or reopen: every driver's list may change.
            e.toDrivers("pending");
            e.toStaff("bookings");
        }
    }

    @Component
    public static class Notifications {
        private final ObjectProvider<LiveEvents> events;

        public Notifications(ObjectProvider<LiveEvents> events) {
            this.events = events;
        }

        @PostPersist
        @PostUpdate
        @PostRemove
        void changed(DashboardNotification n) {
            LiveEvents e = events.getIfAvailable();
            if (e != null && n.getRole() != null) e.toUser(n.getRole(), n.getUserId(), "notifications");
        }
    }

    @Component
    public static class Messages {
        private final ObjectProvider<LiveEvents> events;

        public Messages(ObjectProvider<LiveEvents> events) {
            this.events = events;
        }

        @PostPersist
        void sent(Message m) {
            LiveEvents e = events.getIfAvailable();
            if (e != null) e.toBookingParticipants(m.getBookingId(), "messages:" + m.getBookingId());
        }
    }

    @Component
    public static class Queue {
        private final ObjectProvider<LiveEvents> events;

        public Queue(ObjectProvider<LiveEvents> events) {
            this.events = events;
        }

        @PostPersist
        @PostUpdate
        @PostRemove
        void changed(QueueTicket t) {
            LiveEvents e = events.getIfAvailable();
            if (e != null) e.toDrivers("queue");
        }
    }

    @Component
    public static class Payouts {
        private final ObjectProvider<LiveEvents> events;

        public Payouts(ObjectProvider<LiveEvents> events) {
            this.events = events;
        }

        @PostPersist
        @PostUpdate
        void changed(PayoutRequest p) {
            LiveEvents e = events.getIfAvailable();
            if (e == null) return;
            e.toUser(Role.DRIVER, p.getDriverId(), "payouts");
            e.toStaff("payouts");
        }
    }

    @Component
    public static class Insights {
        private final ObjectProvider<LiveEvents> events;

        public Insights(ObjectProvider<LiveEvents> events) {
            this.events = events;
        }

        @PostPersist
        @PostUpdate
        void changed(OperationalInsight i) {
            LiveEvents e = events.getIfAvailable();
            if (e != null && i.getSeverity() == OperationalInsight.Severity.CRITICAL) e.toStaff("alerts");
        }
    }

    @Component
    public static class Documents {
        private final ObjectProvider<LiveEvents> events;

        public Documents(ObjectProvider<LiveEvents> events) {
            this.events = events;
        }

        @PostPersist
        @PostUpdate
        void changed(DriverDocument d) {
            LiveEvents e = events.getIfAvailable();
            if (e == null) return;
            e.toUser(Role.DRIVER, d.getDriverId(), "documents");
            e.toStaff("drivers");
        }
    }
}
