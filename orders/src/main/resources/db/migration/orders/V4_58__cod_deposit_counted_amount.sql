-- Discussion-4 (CASH · G1): the station cashier's INDEPENDENT count of the cash at handoff, so the
-- rider's self-declared amount is verified against a real second count instead of simply trusted.
-- amount_paise = the DA's declared figure (unchanged); counted_amount_paise = what the station counted.
-- A mismatch routes the deposit to DISCREPANCY (nothing moves until it's resolved). Nullable: only set
-- from the verified-count handoff onward.
ALTER TABLE cod_cash_deposit ADD COLUMN counted_amount_paise BIGINT;

COMMENT ON COLUMN cod_cash_deposit.counted_amount_paise IS
    'The station''s independent count of the cash at handoff; a mismatch vs amount_paise → DISCREPANCY.';
