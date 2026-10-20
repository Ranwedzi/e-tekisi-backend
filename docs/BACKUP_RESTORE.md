# Backup & Restore — Tekisi Postgres

## Single server (`docker-compose.prod.yml`) — start here

The `backup` service (`deploy/backup/`) takes a `pg_dump` at start-up and
then every day at `BACKUP_AT` (default 02:00, `TZ` Africa/Johannesburg).
That is an **RPO of up to 24 hours**; if that's too much, add WAL archiving
as described under "Larger deployments" below.

Each dump is checked with `pg_restore --list` before it counts, kept in the
`tekisi_backups` volume for `BACKUP_KEEP_DAYS` (14), and optionally:

- **encrypted** to an [age](https://age-encryption.org) public key
  (`BACKUP_AGE_RECIPIENT`). The private key never goes on the server, so
  someone who gets into the server can't read old backups;
- **copied off-site** with rclone (`BACKUP_RCLONE_REMOTE`, any rclone
  destination, credentials inline). The service refuses to copy an
  unencrypted dump off-site. Set the bucket's own lifecycle rule (e.g. 90
  days) for off-site retention.

A failed backup is logged and, if `BACKUP_ALERT_WEBHOOK` is set, posted
there. The container turns **unhealthy** when the last good backup is over
26 hours old (`docker compose ps` shows it).

### Set-up

```bash
# On your own machine, not the server. Keep key.txt in your password manager.
age-keygen -o key.txt          # prints "Public key: age1..."
```

Put the public key in `BACKUP_AGE_RECIPIENT` and the destination in
`BACKUP_RCLONE_REMOTE` in `.env.prod`, then `up -d` the stack. Check the first
run:

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod logs backup
```

Also keep **`DATA_ENCRYPTION_KEY`** with the backups' private key: payout
bank details in the dump are encrypted with it and unreadable without it.

### Back up now (e.g. before an upgrade)

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod run --rm backup now
```

### Restore drill (monthly, and after changing anything above)

Restores a dump into a throwaway database inside the backup container,
checks the Flyway version and the ledger, and shows row counts next to the
live database. The live database isn't touched.

```bash
# newest local dump, unencrypted
docker compose -f docker-compose.prod.yml --env-file .env.prod run --rm backup restore-drill

# encrypted dump: mount the private key for the duration of the drill
docker compose -f docker-compose.prod.yml --env-file .env.prod run --rm   -v "$HOME/key.txt:/key.txt:ro" -e AGE_IDENTITY_FILE=/key.txt   backup restore-drill etekisi-20261020T000000Z.dump.age
```

It ends with `RESTORE DRILL PASSED` (or `FAILED`, exit code 1). Note the
date and the time it took in your ops log. At least once, also drill from the
**off-site** copy: download it with rclone onto another machine and run the
same command there, because that's the copy you'll need if the server is lost.

### Restoring for real

1. Stop the app: `docker compose ... stop backend frontend`.
2. Take one more dump of the current state if the database still runs
   (`run --rm backup now`), in case you need something from it later.
3. Restore over the live database (decrypt first if needed:
   `age -d -i key.txt -o etekisi.dump etekisi-....dump.age`):

   ```bash
   docker compose ... run --rm -v "$PWD/etekisi.dump:/restore.dump:ro" --entrypoint sh backup -c      'pg_restore --clean --if-exists --no-owner --no-privileges -d "$PGDATABASE" /restore.dump'
   ```

4. Start the app: `docker compose ... up -d`. Flyway checks the schema on
   start; the backend won't start if it doesn't match.

---

## Larger deployments (RPO minutes)

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

- **GPS pings, chat, notifications and old driver locations** are deleted
  by `DataRetentionJob` after `RETENTION_*_DAYS` (POPIA). A backup holds
  them for up to `BACKUP_KEEP_DAYS` (plus the off-site retention) longer;
  say so in the privacy policy.
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
