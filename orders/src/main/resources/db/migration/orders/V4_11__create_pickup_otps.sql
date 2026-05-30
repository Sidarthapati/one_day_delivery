-- PR #9: pickup OTP table for DA pickup verification.
--
-- Flow: when a shipment enters PICKUP_ASSIGNED, PickupOtpService generates a
-- 4-digit OTP, stores only the BCrypt hash here, and sends the cleartext to
-- the sender via SMS. The DA app calls POST /internal/v1/shipments/{ref}/pickup-otp/verify;
-- on success the state machine transitions PICKUP_ASSIGNED → PICKED_UP.
--
-- Design choices:
--   - otp_hash: BCrypt of the 4-digit OTP; cleartext is never stored.
--   - expires_at: 10 minutes from generation; checked at verify time.
--   - used: set to TRUE on first successful verify; prevents replay within TTL window.
--   - resend_count: incremented on each /resend; capped at 3 by application logic.
--   - One active OTP per shipment at a time (uq_pickup_otps_shipment enforces this).
--     On resend the old row is deleted and a fresh row is inserted.

CREATE TABLE pickup_otps (
    id             UUID         NOT NULL DEFAULT gen_random_uuid(),
    shipment_id    UUID         NOT NULL REFERENCES shipments(id),
    otp_hash       VARCHAR(60)  NOT NULL,   -- BCrypt output is always 60 chars
    expires_at     TIMESTAMPTZ  NOT NULL,
    used           BOOLEAN      NOT NULL DEFAULT FALSE,
    resend_count   SMALLINT     NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_pickup_otps PRIMARY KEY (id)
);

-- One active OTP row per shipment; deleted and re-inserted on resend.
CREATE UNIQUE INDEX uq_pickup_otps_shipment ON pickup_otps(shipment_id);

-- Purge job / cleanup queries filter on expires_at.
CREATE INDEX idx_pickup_otps_expires ON pickup_otps(expires_at);
