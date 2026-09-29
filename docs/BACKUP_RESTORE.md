# Backup & Restore — Tekisi Postgres

## Objectives

- **RPO (Recovery Point Objective):** ≤ 5 minutes of data loss.
- **RTO (Recovery Time Objective):** ≤ 15 minutes from declaration to live.

These targets assume a single-region single-leader deployment and the
backup strategy below. Multi-region active/active is Phase 4.3.

## Strategy

Two complementary mechanisms:

1. **Nightly base backup** via `pg_basebackup`, written to S3 with
   server-side encryption and a 30-day lifecycle.
2. **Continuous WAL archiving** via `archive_command` to the same bucket.
   At restore time we replay WAL on top of the most recent base backup.

```
postgres.conf
---------------
wal_level             = replica
archive_mode          = on
archive_command       = 'aws s3 cp %p s3://tekisi-wal/%f'
archive_timeout       = 300        # at most 5 min of WAL pending => RPO 5m
max_wal_senders       = 3
```

### Base backup — daily cron

```bash
#!/usr/bin/env bash
set -euo pipefail
DEST=/var/backups/tekisi/$(date +%Y-%m-%dT%H-%M-%S)
mkdir -p "$DEST"
PGPASSWORD="$REPLICATOR_PASSWORD" pg_basebackup \
    -h db.internal -U replicator -D "$DEST" -F tar -z -P -X stream
aws s3 sync "$DEST" "s3://tekisi-base/$(basename "$DEST")/"
```

### Verification

The backup is **not** considered complete until a successful restore drill is
executed against staging — this is the only check that catches missing WAL
segments, encryption misconfiguration, or expired credentials.

## Restore drill (run quarterly)

```bash
# 1. Provision a fresh staging Postgres
docker run -d --name tekisi-restore -p 6543:5432 \
    -v $(pwd)/restore-data:/var/lib/postgresql/data \
    postgres:16-alpine

# 2. Stop, wipe, untar latest base backup
docker stop tekisi-restore
sudo rm -rf restore-data/*
LATEST=$(aws s3 ls s3://tekisi-base/ | sort | tail -1 | awk '{print $4}')
aws s3 sync "s3://tekisi-base/$LATEST" restore-data/
sudo tar -xzf restore-data/base.tar.gz -C restore-data/

# 3. Configure recovery
cat >> restore-data/postgresql.auto.conf <<EOF
restore_command = 'aws s3 cp s3://tekisi-wal/%f %p'
recovery_target_time = '${POINT_IN_TIME:-now}'
EOF
touch restore-data/recovery.signal

# 4. Start, validate
docker start tekisi-restore
docker exec tekisi-restore psql -U postgres -d etekisi \
    -c "SELECT count(*) FROM agent_schema.decision_audit"
```

### Drill checklist

- [ ] Backup is < 24h old.
- [ ] WAL replay completes without warnings.
- [ ] Row count of `agent_schema.decision_audit` matches the production count
      from the last hour (within RPO).
- [ ] `SELECT max(computedAt) FROM agent_schema.decision_audit` is within
      5 minutes of `now()`.
- [ ] `Flyway` schema_history is intact (`select count(*) from
      flyway_schema_history`).
- [ ] Application can boot against the restored DB without
      `ddl-auto=validate` errors.
- [ ] Total drill time recorded in `docs/incident-log.md`.

## Encryption + Access

- S3 bucket has SSE-KMS enabled with a tekisi-managed key.
- Only the `backup` and `restore-operator` IAM roles have read access.
- WAL bucket lifecycle: keep all segments for 7 days (covers any reasonable
  point-in-time recovery), then transition to Glacier for 90 days.

## What is NOT backed up here

- **GPS pings** beyond 7 days are pruned by application policy and not
  considered durable. If you need them retained, change
  `tekisi.gps.retention-days` and the WAL alone is not enough.
- **Idempotency keys** (`agent_schema.idempotency_keys`) are restorable but
  not safety-critical; expired entries are pruned daily.
- **In-flight HTTP requests** at the moment of failure are lost — this is
  why Phase 1.9 introduced `Idempotency-Key`. Mobile clients MUST retry with
  the same key.

## Runbook reference

When invoking restore in incident response:

1. Declare incident in PagerDuty (severity 1).
2. Identify the recovery target time (default: `now()`).
3. Execute the steps in **Restore drill** against the production target.
4. Update DNS/load-balancer to point at the new instance.
5. Run smoke test (`tests/smoke.sh`) and verify three booking flows.
6. Post-incident: write a 1-page review within 48h.
