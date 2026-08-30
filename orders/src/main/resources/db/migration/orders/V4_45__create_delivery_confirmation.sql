-- M4 receiver delivery confirmation. When the assigned flight departs (or, same-city, the parcel is
-- sorted for delivery), the receiver is emailed a no-login accept/reject link with an ETD. Silence =
-- accept; an explicit reject re-parks the delivery for the receiver's chosen next-day shift. One live
-- confirmation per shipment; the token is stored only as a SHA-256 hash (the cleartext rides the link).
CREATE TABLE delivery_confirmation (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    shipment_id    UUID NOT NULL,
    attempt_no     INT  NOT NULL DEFAULT 1,
    token_hash     VARCHAR(64) NOT NULL,                 -- SHA-256 hex of the opaque link token
    status         VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING | ACCEPTED | REJECTED | EXPIRED
    eta            TIMESTAMPTZ,                           -- computed expected delivery instant
    eta_shift      VARCHAR(20),                           -- SHIFT_1 | SHIFT_2 the ETA resolved to
    eta_day        VARCHAR(10),                           -- TODAY | NEXT_DAY (human framing)
    channel        VARCHAR(20) NOT NULL DEFAULT 'EMAIL',
    response_shift VARCHAR(20),                           -- SHIFT_1 | SHIFT_2 the receiver picked on reject
    sent_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at     TIMESTAMPTZ NOT NULL,
    responded_at   TIMESTAMPTZ
);

CREATE UNIQUE INDEX ux_delivery_confirmation_token ON delivery_confirmation (token_hash);
-- One live (PENDING) confirmation per shipment — the prompt idempotency guard.
CREATE UNIQUE INDEX ux_delivery_confirmation_live ON delivery_confirmation (shipment_id)
    WHERE status = 'PENDING';
