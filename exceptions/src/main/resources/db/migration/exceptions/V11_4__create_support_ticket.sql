-- M11 support tickets: customer/merchant-initiated help requests and "call me" callbacks. Unlike
-- exception_case (opened by a failure event, always bound to one shipment), a ticket is raised by a
-- person, is shipment-OPTIONAL, and carries the reporter's identity + contact. Enum-like columns are
-- VARCHAR + app-side @Enumerated(STRING), same convention as the rest of M11.

CREATE TABLE support_ticket (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    raised_by_user_id UUID        NOT NULL,               -- the customer/merchant (M1 user id)
    raised_by_role    VARCHAR(32),                        -- B2C_CUSTOMER | C2C_CUSTOMER | B2B_USER
    channel           VARCHAR(16) NOT NULL,               -- TICKET | CALLBACK
    shipment_ref      VARCHAR(64),                        -- optional; validated best-effort at intake
    subject           VARCHAR(200),
    body              TEXT,
    contact_phone     VARCHAR(20),                        -- for CALLBACK ("call me on…")
    status            VARCHAR(16) NOT NULL DEFAULT 'OPEN', -- OPEN | IN_PROGRESS | RESOLVED | CANCELLED
    assigned_to       VARCHAR(64),                        -- ops agent handling it (M1 user id)
    resolution_note   TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at       TIMESTAMPTZ
);

-- Ops queue: open tickets, freshest first. Partial index keeps the read over just the live set.
CREATE INDEX idx_support_ticket_open ON support_ticket (created_at DESC) WHERE resolved_at IS NULL;
-- A customer's own tickets, newest first.
CREATE INDEX idx_support_ticket_raiser ON support_ticket (raised_by_user_id, created_at DESC);
