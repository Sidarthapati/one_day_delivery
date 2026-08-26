-- Multiple service accounts: a B2B account can have many users. Until now a user linked to an account
-- only via b2b_accounts.owner_user_id (one owner). This membership table generalises that — one account
-- ↔ many members. The DB permits a user on more than one account (unique per account+user, not per user):
-- the seeded demo principal owns BOTH demo accounts (V4_16), so a per-user unique would abort the backfill
-- below. The invite flow still enforces one-account-per-user for real invites; resolution is deterministic
-- (earliest membership). email/name are denormalised for a display-only member list.

CREATE TABLE b2b_account_member (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    b2b_account_id UUID        NOT NULL,
    user_id        UUID        NOT NULL,
    role           VARCHAR(16) NOT NULL DEFAULT 'MEMBER',   -- OWNER | MEMBER
    email          VARCHAR(254),
    name           VARCHAR(200),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_b2b_member_account_user UNIQUE (b2b_account_id, user_id)
);

-- The member list for one account, owner first.
CREATE INDEX idx_b2b_member_account ON b2b_account_member (b2b_account_id, created_at);

-- Backfill: every existing account's owner becomes its OWNER member, so the membership-based account
-- resolution keeps working for everyone who already had an account.
INSERT INTO b2b_account_member (id, b2b_account_id, user_id, role, email, name, created_at, updated_at)
SELECT gen_random_uuid(), id, owner_user_id, 'OWNER', billing_email, account_name, now(), now()
FROM b2b_accounts
WHERE owner_user_id IS NOT NULL;
