-- M11 support-ticket conversation thread: a two-way message log on a support_ticket. Either side posts —
-- the raiser (customer/merchant) or an ops agent — distinguished by from_agent. Enum-like author_role is
-- VARCHAR + app-side @Enumerated-free (a plain role string), same convention as support_ticket.

CREATE TABLE support_ticket_message (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_id      UUID        NOT NULL,        -- the support_ticket this belongs to
    author_user_id UUID        NOT NULL,        -- who wrote it (M1 user id)
    author_role    VARCHAR(40),                 -- the author's role at post time
    from_agent     BOOLEAN     NOT NULL,        -- true = ops agent, false = the ticket's raiser
    body           TEXT        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The thread for one ticket, in reading order.
CREATE INDEX idx_support_ticket_message_thread ON support_ticket_message (ticket_id, created_at);
