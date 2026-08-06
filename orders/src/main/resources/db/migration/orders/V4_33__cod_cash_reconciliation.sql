-- Phase I: DA COD cash reconciliation.

-- Who physically collected the cash — the delivery associate who dropped the parcel. Set when a
-- COD collection is marked COLLECTED (from the transition actor). Null for older rows / hub-collect.
ALTER TABLE cod_collection ADD COLUMN collected_by_da_id UUID;
CREATE INDEX idx_cod_collection_da ON cod_collection (collected_by_da_id) WHERE collected_by_da_id IS NOT NULL;

-- A delivery associate's declared cash deposit (handed to bank/hub), reconciled by admin against the
-- cash they were expected to be holding (Σ COLLECTED COD attributed to that DA).
CREATE TABLE cod_cash_deposit (
    id            UUID        PRIMARY KEY,
    da_user_id    UUID        NOT NULL,
    amount_paise  BIGINT      NOT NULL,
    deposit_ref   VARCHAR(80),
    note          VARCHAR(300),
    status        VARCHAR(20) NOT NULL DEFAULT 'DEPOSITED',
    reconciled_by UUID,
    reconciled_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_cod_cash_deposit_da ON cod_cash_deposit (da_user_id, created_at DESC);
