package com.etekisi.etekisi.agent.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Daily retention job for {@code agent_schema.decision_audit}.
 *
 * <p>Bound: deletes rows older than {@code tekisi.agent.audit.retention-days}
 * (default 90). When the property is &le; 0 the job is a no-op — this is how
 * the test profile disables retention without deleting the bean.
 *
 * <p>Runs daily at 03:15 server time to land safely after midnight aggregation
 * jobs and well before morning traffic. Designed to be idempotent: a missed
 * day catches up automatically because the cutoff is computed at run-time.
 *
 * <p>Anti-data-loss posture: the bulk DELETE is the ONLY mutation pathway on
 * the audit table. Production grants should restrict DELETE to the role this
 * job runs under; the application user remains INSERT-only.
 */
@Component
public class AuditRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(AuditRetentionJob.class);

    private final DecisionAuditRepository repo;
    private final int retentionDays;

    public AuditRetentionJob(DecisionAuditRepository repo,
                             @Value("${tekisi.agent.audit.retention-days:90}") int retentionDays) {
        this.repo = repo;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "${tekisi.agent.audit.retention-cron:0 15 3 * * *}")
    public void purge() {
        if (retentionDays <= 0) {
            log.debug("agent.audit.retention.skipped retention-days={}", retentionDays);
            return;
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        try {
            int deleted = repo.deleteOlderThan(cutoff);
            log.info("agent.audit.retention.purged retentionDays={} cutoff={} deleted={}",
                    retentionDays, cutoff, deleted);
        } catch (RuntimeException e) {
            // Never let the job poison the scheduler; alert via logs + metrics.
            log.error("agent.audit.retention.failed cutoff={} error={}", cutoff, e.getMessage());
        }
    }
}
