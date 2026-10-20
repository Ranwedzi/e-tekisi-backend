-- V20: ShedLock. Scheduled jobs that change data take a row here first, so
-- with several backend instances each job runs on one of them at a time.

CREATE TABLE IF NOT EXISTS agent_schema.shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
