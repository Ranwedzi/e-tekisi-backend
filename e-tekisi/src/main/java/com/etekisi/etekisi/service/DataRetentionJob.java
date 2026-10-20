package com.etekisi.etekisi.service;

import com.etekisi.etekisi.agent.trip.TripGpsPingRepository;
import com.etekisi.etekisi.repository.DriverLocationRepository;
import com.etekisi.etekisi.repository.MessageRepository;
import com.etekisi.etekisi.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.function.Function;

/**
 * Daily purge of personal data we no longer need (POPIA section 14): trip GPS
 * history, drivers' last-known positions, chat and notifications. Each kind
 * has its own {@code tekisi.retention.*-days}; 0 or less turns that one off.
 *
 * <p>Same shape as {@code AuditRetentionJob}: the cutoff is computed at run
 * time so a missed day catches up, and each purge runs in its own transaction
 * so one failure doesn't stop the others or the scheduler.
 */
@Component
public class DataRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(DataRetentionJob.class);

    private final TripGpsPingRepository gpsPings;
    private final DriverLocationRepository locations;
    private final MessageRepository messages;
    private final NotificationRepository notifications;
    private final TransactionTemplate tx;
    private final int gpsDays;
    private final int locationDays;
    private final int messageDays;
    private final int notificationDays;

    public DataRetentionJob(TripGpsPingRepository gpsPings, DriverLocationRepository locations,
                            MessageRepository messages, NotificationRepository notifications,
                            TransactionTemplate tx,
                            @Value("${tekisi.retention.trip-gps-days:90}") int gpsDays,
                            @Value("${tekisi.retention.driver-location-days:7}") int locationDays,
                            @Value("${tekisi.retention.messages-days:180}") int messageDays,
                            @Value("${tekisi.retention.notifications-days:90}") int notificationDays) {
        this.gpsPings = gpsPings;
        this.locations = locations;
        this.messages = messages;
        this.notifications = notifications;
        this.tx = tx;
        this.gpsDays = gpsDays;
        this.locationDays = locationDays;
        this.messageDays = messageDays;
        this.notificationDays = notificationDays;
    }

    @Scheduled(cron = "${tekisi.retention.cron:0 30 3 * * *}")
    public void purge() {
        purge("trip-gps", gpsDays, cutoff -> gpsPings.deleteOlderThanExceptFlagged(cutoff));
        purge("driver-location", locationDays, cutoff -> locations.deleteOlderThan(local(cutoff)));
        purge("messages", messageDays, cutoff -> messages.deleteOlderThan(local(cutoff)));
        purge("notifications", notificationDays, cutoff -> notifications.deleteOlderThan(local(cutoff)));
    }

    private void purge(String kind, int days, Function<Instant, Integer> delete) {
        if (days <= 0) {
            log.debug("retention.skipped kind={} days={}", kind, days);
            return;
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(days));
        try {
            Integer deleted = tx.execute(status -> delete.apply(cutoff));
            log.info("retention.purged kind={} days={} cutoff={} deleted={}", kind, days, cutoff, deleted);
        } catch (RuntimeException e) {
            log.error("retention.failed kind={} cutoff={} error={}", kind, cutoff, e.getMessage());
        }
    }

    /** The chat, notification and location tables store server-local LocalDateTime. */
    private static LocalDateTime local(Instant instant) {
        return LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault());
    }
}
