# Backup runner for the prod stack: same Postgres major version as the
# database (pg_dump must not be older than the server), plus
#   age    - encrypts dumps to a public key, so the server can't read old
#            backups and an off-site copy is useless without the private key
#   rclone - copies them off-site (S3, Backblaze B2, Google Drive, SFTP, ...)
#   curl   - posts a message when a backup fails
FROM postgres:16-alpine

RUN apk add --no-cache age rclone curl tzdata \
 && mkdir -p /backups \
 && chown postgres:postgres /backups

COPY --chmod=755 backup.sh restore-drill.sh /usr/local/bin/

# Not root: initdb (used by the restore drill) refuses to run as root.
USER postgres
VOLUME /backups
ENTRYPOINT ["/usr/local/bin/backup.sh"]
CMD ["loop"]
