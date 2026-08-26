-- Notification outbox: one row per rendered message to one recipient on one channel. The service
-- enqueues PENDING; a scheduled drain delivers (SENT) or records the failure (FAILED) and retries
-- until the attempt cap. Transactional-outbox pattern, mirroring webhook_delivery (V4_29). Enum-like
-- columns are VARCHAR + app-side @Enumerated(STRING).
CREATE TABLE notification_log (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type  VARCHAR(32)  NOT NULL,               -- NotificationEventType name
    channel     VARCHAR(8)   NOT NULL,               -- EMAIL | SMS
    recipient   VARCHAR(254) NOT NULL,               -- email address or E.164 phone
    subject     VARCHAR(300),
    body        TEXT         NOT NULL,
    status      VARCHAR(12)  NOT NULL DEFAULT 'PENDING', -- PENDING | SENT | FAILED
    attempts    INTEGER      NOT NULL DEFAULT 0,
    error       TEXT,
    sent_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- The drain query: undelivered rows (PENDING/FAILED), oldest first. Partial index keeps it over just
-- the live set — SENT rows (the vast majority over time) are excluded.
CREATE INDEX idx_notification_log_undelivered ON notification_log (created_at)
    WHERE status <> 'SENT';
