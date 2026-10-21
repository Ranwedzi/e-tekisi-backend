-- V24: requests that nobody takes expire, and scheduled trips are real.
--   scheduled_at        when the commuter wants to leave (null = now)
--   released_at         when drivers could first see the request (null = a
--                       scheduled trip not yet shown; it's released shortly
--                       before scheduled_at)
--   request_expires_at  a PENDING request still unanswered then becomes EXPIRED
ALTER TABLE commuter_schema.bookings ADD COLUMN IF NOT EXISTS scheduled_at TIMESTAMPTZ;
ALTER TABLE commuter_schema.bookings ADD COLUMN IF NOT EXISTS released_at TIMESTAMPTZ;
ALTER TABLE commuter_schema.bookings ADD COLUMN IF NOT EXISTS request_expires_at TIMESTAMPTZ;

-- Old scheduled times were free text from a datetime-local field (South African time).
UPDATE commuter_schema.bookings
   SET scheduled_at = (scheduled_time::timestamp AT TIME ZONE 'Africa/Johannesburg')
 WHERE scheduled_time ~ '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?$';

-- Requests already open were visible to drivers; give them the normal time to find one.
UPDATE commuter_schema.bookings
   SET released_at = now(), request_expires_at = now() + interval '15 minutes'
 WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_bookings_pending_timers
    ON commuter_schema.bookings (status, released_at, request_expires_at);
