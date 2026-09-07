-- Discussion-3 (ix): complete COD settlement from hub-collection to bank-confirmed.
-- Extends the DA cash deposit from a single self-declared row into a verified custody chain
-- (DEPOSITED -> HANDED_OVER -> BANK_DEPOSITED -> BANK_CONFIRMED), adds an OTP-verified DA->station
-- handoff, and gates vendor remittance so it only draws collections whose cash has actually reached
-- our bank (settlement_state = BANK_SETTLED, allocated FIFO when a deposit is bank-confirmed).

-- 1. Custody chain fields on the deposit (status column is already VARCHAR(20), wide enough).
ALTER TABLE cod_cash_deposit
    ADD COLUMN received_by       UUID,                 -- station user who confirmed receipt (OTP)
    ADD COLUMN handed_over_at    TIMESTAMPTZ,
    ADD COLUMN bank_deposit_ref  VARCHAR(80),          -- the station's actual bank slip reference
    ADD COLUMN bank_deposited_at TIMESTAMPTZ,
    ADD COLUMN bank_credit_ref   VARCHAR(80),          -- finance/provider ref for the confirmed credit
    ADD COLUMN bank_confirmed_at TIMESTAMPTZ;

-- 2. OTP-verified DA -> station cash handoff. One active row per deposit (mirrors pickup_otps):
--    BCrypt-hashed 4-digit code, single-use, append-only except `used`.
CREATE TABLE cash_handoff_otp (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    deposit_id   UUID        NOT NULL,
    otp_hash     VARCHAR(60) NOT NULL,                 -- BCrypt output is always 60 chars
    expires_at   TIMESTAMPTZ NOT NULL,
    used         BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()    -- BaseEntity carries updated_at
);
CREATE UNIQUE INDEX uq_cash_handoff_otp_deposit ON cash_handoff_otp (deposit_id);

-- 3. Per-collection settlement state — the link between "cash reached the bank" and "this vendor's
--    money is remittable". New collections start IN_CUSTODY; a collection flips BANK_SETTLED (FIFO)
--    when a bank-confirmed deposit by the same DA covers it.
ALTER TABLE cod_collection
    ADD COLUMN settlement_state VARCHAR(20) NOT NULL DEFAULT 'IN_CUSTODY',
    ADD COLUMN bank_settled_at  TIMESTAMPTZ;

-- FIFO scan: a DA's oldest still-in-custody collections, and the remittable filter.
CREATE INDEX idx_cod_collection_settlement
    ON cod_collection (collected_by_da_id, settlement_state, collected_at);

-- Grandfather existing rows: everything collected before this migration keeps the old behaviour
-- (remittable as before), so pilot data isn't stranded IN_CUSTODY. Only new collections must earn
-- BANK_SETTLED through the verified chain.
UPDATE cod_collection
SET settlement_state = 'BANK_SETTLED', bank_settled_at = COALESCE(collected_at, now())
WHERE state IN ('COLLECTED', 'REMITTED');
