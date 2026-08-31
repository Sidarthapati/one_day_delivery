-- vii: per-DA COD cash ledger. The company is owed the cash its riders collect on delivery until they
-- deposit it. Until now a DA's position was recomputed on the fly (Σ collected − Σ deposited) with no
-- audit trail; this makes it an append-only ledger with a running balance, exactly like the merchant
-- wallet (wallet_transaction + b2b_accounts.wallet_balance_paise).

-- Running balance = cash the DA is currently holding on the company's behalf. Lockable row that
-- serialises concurrent postings, mirroring b2b_accounts.wallet_balance_paise.
CREATE TABLE IF NOT EXISTS da_cod_balance (
    da_user_id         UUID PRIMARY KEY,
    cash_in_hand_paise BIGINT      NOT NULL DEFAULT 0,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Append-only history; the balance above is reconstructable from it. Never mutated after insert.
CREATE TABLE IF NOT EXISTS da_cod_ledger (
    id                  UUID        PRIMARY KEY,
    da_user_id          UUID        NOT NULL,
    -- COLLECTION (+ took cash at delivery), DEPOSIT (- handed cash in), ADJUSTMENT (± admin correction)
    type                VARCHAR(20) NOT NULL,
    amount_paise        BIGINT      NOT NULL,   -- signed: + increases cash-in-hand, - decreases it
    balance_after_paise BIGINT      NOT NULL,
    reference           VARCHAR(80),            -- shipment_ref / deposit_ref, for reconciliation
    description         VARCHAR(300),
    created_by          UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_da_cod_ledger_da ON da_cod_ledger (da_user_id, created_at DESC);

-- NOTE: the ledger is authoritative for cash movements from here on. Existing cod_collection /
-- cod_cash_deposit rows are NOT backfilled — the per-DA time-ordered reconstruction / cutover
-- opening-balance seed is deferred to issue #185. Until then a DA depositing pre-cutover cash can drive
-- the ledger balance negative; that's expected (the ledger simply doesn't yet know their pre-cutover
-- collections), and it's why we do NOT clamp deposits to a non-negative balance. The on-the-fly
-- collected/deposited sums remain for historical continuity.
