#!/bin/sh
# Restore drill: proves a backup can actually be restored, without touching
# the live database. Restores into a throwaway Postgres inside this
# container, checks it, compares row counts with the live database, and
# deletes it again.
#
#   docker compose -f docker-compose.prod.yml --env-file .env.prod \
#     run --rm backup restore-drill [file]
#
# [file] defaults to the newest dump in /backups. For an encrypted (.age)
# dump, mount the private key and point AGE_IDENTITY_FILE at it:
#   ... run --rm -v ~/tekisi-backup-key.txt:/key.txt:ro \
#       -e AGE_IDENTITY_FILE=/key.txt backup restore-drill
set -eu

BACKUP_DIR=/backups
WORK=$(mktemp -d /tmp/drill.XXXXXX)
PORT=5499
started=$(date +%s)

cleanup() {
  pg_ctl -D "$WORK/data" -m immediate stop >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT

file="${1:-}"
if [ -z "$file" ]; then
  file=$(ls -t "$BACKUP_DIR"/etekisi-*.dump "$BACKUP_DIR"/etekisi-*.dump.age 2>/dev/null | head -1 || true)
  [ -n "$file" ] || { echo "No backups in $BACKUP_DIR" >&2; exit 1; }
fi
case "$file" in /*) ;; *) file="$BACKUP_DIR/$file" ;; esac
echo "Restore drill for $file ($(du -h "$file" | cut -f1), made $(date -r "$file" '+%Y-%m-%d %H:%M %Z'))"

dump="$file"
case "$file" in
  *.age)
    [ -n "${AGE_IDENTITY_FILE:-}" ] || { echo "Encrypted dump: set AGE_IDENTITY_FILE to the private key file" >&2; exit 1; }
    age -d -i "$AGE_IDENTITY_FILE" -o "$WORK/drill.dump" "$file"
    dump="$WORK/drill.dump"
    ;;
esac

initdb -D "$WORK/data" -U drill --auth=trust --no-locale -E UTF8 >/dev/null 2>&1
pg_ctl -D "$WORK/data" -o "-p $PORT -k $WORK -c listen_addresses=''" -w -l "$WORK/pg.log" start >/dev/null
createdb -h "$WORK" -p "$PORT" -U drill etekisi
pg_restore -h "$WORK" -p "$PORT" -U drill -d etekisi --no-owner --no-privileges --exit-on-error "$dump"

q()    { psql -h "$WORK" -p "$PORT" -U drill -d etekisi -Atc "$1"; }
live() { psql -Atc "$1" 2>/dev/null || echo "?"; }

failed=0
version=$(q "select max(version::int) from agent_schema.flyway_schema_history where success and version is not null")
echo "Schema version (Flyway): V$version"
[ -n "$version" ] || { echo "FAIL: no Flyway history"; failed=1; }

printf '%-34s %10s %10s\n' "table" "backup" "live now"
for t in commuter_schema.users driver_schema.drivers commuter_schema.bookings \
         commuter_schema.wallets commuter_schema.wallet_transactions driver_schema.payout_requests; do
  printf '%-34s %10s %10s\n' "$t" "$(q "select count(*) from $t")" "$(live "select count(*) from $t")"
done

# Ledger invariant: a wallet's balance equals the balance_after of its latest ledger row.
drift=$(q "select count(*) from commuter_schema.wallets w
           join lateral (select balance_after from commuter_schema.wallet_transactions t
                         where t.owner_id = w.user_id and t.owner_role = 'COMMUTER'
                         order by t.id desc limit 1) last on true
           where w.role = 'COMMUTER' and last.balance_after <> w.balance")
if [ "$drift" = "0" ]; then
  echo "Ledger check: wallet balances match the ledger"
else
  echo "WARN: $drift wallet(s) whose balance differs from their latest ledger entry"
fi

echo "Restored and checked in $(( $(date +%s) - started ))s"
if [ "$failed" -ne 0 ]; then
  echo "RESTORE DRILL FAILED"
  exit 1
fi
echo "RESTORE DRILL PASSED"
