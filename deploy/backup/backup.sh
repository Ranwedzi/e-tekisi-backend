#!/bin/sh
# e-Tekisi database backups (runs in the "backup" service of docker-compose.prod.yml).
#
#   backup.sh loop    default: back up at start-up, then every day at BACKUP_AT
#   backup.sh now     one backup, then exit
#
# Each backup is a pg_dump custom-format file in /backups, checked with
# pg_restore --list before it counts. Optional:
#   BACKUP_AGE_RECIPIENT  age public key (age1...): dumps are encrypted to it
#   BACKUP_RCLONE_REMOTE  rclone destination (e.g. b2:tekisi-backups); needs
#                         BACKUP_AGE_RECIPIENT, so nothing leaves unencrypted.
#                         Configure the remote with RCLONE_CONFIG_* variables.
#   BACKUP_ALERT_WEBHOOK  gets {"text": ...} when a backup fails
#   BACKUP_KEEP_DAYS      local copies older than this are deleted (14)
#   BACKUP_AT             daily time, HH:MM in TZ (02:00)
# Writes /backups/last-success (epoch seconds) for the container healthcheck.
set -eu

BACKUP_DIR=/backups
KEEP_DAYS="${BACKUP_KEEP_DAYS:-14}"
AT="${BACKUP_AT:-02:00}"

log() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) backup: $*"; }

notify() {
  [ -n "${BACKUP_ALERT_WEBHOOK:-}" ] || return 0
  curl -fsS -m 10 -X POST -H 'Content-Type: application/json' \
    -d "{\"text\":\"e-Tekisi backup FAILED on $(hostname): $1\"}" "$BACKUP_ALERT_WEBHOOK" >/dev/null 2>&1 \
    || log "could not send failure notice"
}

run_backup() {
  stamp=$(date -u +%Y%m%dT%H%M%SZ)
  file="$BACKUP_DIR/etekisi-$stamp.dump"
  rm -f "$BACKUP_DIR"/*.partial

  pg_dump -Fc -Z 6 --no-owner --no-privileges -f "$file.partial" || { log "pg_dump failed"; return 1; }
  pg_restore --list "$file.partial" >/dev/null || { log "dump is not readable"; return 1; }

  if [ -n "${BACKUP_AGE_RECIPIENT:-}" ]; then
    age -r "$BACKUP_AGE_RECIPIENT" -o "$file.age.partial" "$file.partial" || { log "encryption failed"; return 1; }
    rm -f "$file.partial"
    mv "$file.age.partial" "$file.age"
    final="$file.age"
  else
    mv "$file.partial" "$file"
    final="$file"
  fi
  log "wrote $final ($(du -h "$final" | cut -f1))"

  if [ -n "${BACKUP_RCLONE_REMOTE:-}" ]; then
    if [ -z "${BACKUP_AGE_RECIPIENT:-}" ]; then
      log "BACKUP_RCLONE_REMOTE is set but BACKUP_AGE_RECIPIENT isn't; refusing to copy an unencrypted dump off-site"
      return 1
    fi
    rclone copy --retries 3 "$final" "$BACKUP_RCLONE_REMOTE" || { log "off-site copy failed"; return 1; }
    log "copied to $BACKUP_RCLONE_REMOTE"
  fi

  find "$BACKUP_DIR" -maxdepth 1 -name 'etekisi-*' -mtime +"$KEEP_DAYS" -print -delete | sed 's/^/pruned /' || true
  date -u +%s > "$BACKUP_DIR/last-success"
  return 0
}

attempt() {
  if run_backup; then
    log "ok"
  else
    log "FAILED"
    notify "see 'docker compose logs backup'"
    return 1
  fi
}

sleep_until_next() {
  now=$(date +%s)
  target=$(date -d "$AT" +%s)
  [ "$target" -gt "$now" ] || target=$((target + 86400))
  log "next backup at $(date -d "@$target" '+%Y-%m-%d %H:%M %Z')"
  sleep $((target - now))
}

case "${1:-loop}" in
  now)
    attempt
    ;;
  loop)
    attempt || true
    while true; do
      sleep_until_next
      attempt || true
    done
    ;;
  restore-drill)
    shift
    exec /usr/local/bin/restore-drill.sh "$@"
    ;;
  *)
    echo "usage: backup.sh [loop|now|restore-drill [file]]" >&2
    exit 2
    ;;
esac
