-- Last-mile delivery OTP table — the drop-side mirror of pickup_otps (V4_11).
--
-- Flow: when a shipment enters DROP_COLLECTED (DA has the parcel, out for delivery),
-- DeliveryOtpService generates a 4-digit OTP, stores only the BCrypt hash here, and the
-- cleartext is sent to the recipient. The DA app calls
-- POST /internal/v1/shipments/{ref}/delivery-otp/verify; on success the state machine
-- transitions DROP_COLLECTED → DROPPED. This implements the delivery-verification step
-- that was left open as OD-8.
--
-- Design choices mirror pickup_otps:
--   - otp_hash: BCrypt of the 4-digit OTP; cleartext is never stored.
--   - expires_at: TTL from generation; checked at verify time.
--   - used: TRUE on first successful verify; prevents replay within the TTL window.
--   - resend_count: incremented on each /resend; capped by application logic.
--   - One active OTP per shipment (uq_delivery_otps_shipment); old row deleted on resend.

CREATE TABLE delivery_otps (
    id             UUID         NOT NULL DEFAULT gen_random_uuid(),
    shipment_id    UUID         NOT NULL REFERENCES shipments(id),
    otp_hash       VARCHAR(60)  NOT NULL,   -- BCrypt output is always 60 chars
    expires_at     TIMESTAMPTZ  NOT NULL,
    used           BOOLEAN      NOT NULL DEFAULT FALSE,
    resend_count   SMALLINT     NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_delivery_otps PRIMARY KEY (id)
);

-- One active OTP row per shipment; deleted and re-inserted on resend.
CREATE UNIQUE INDEX uq_delivery_otps_shipment ON delivery_otps(shipment_id);

-- Purge job / cleanup queries filter on expires_at.
CREATE INDEX idx_delivery_otps_expires ON delivery_otps(expires_at);
