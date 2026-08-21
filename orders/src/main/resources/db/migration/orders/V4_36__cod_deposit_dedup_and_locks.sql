-- RC5 (vuln-0012): a DA COD cash deposit could be submitted twice (no dedup), inflating reconciled
-- cash. deposit_ref is the DA's idempotency handle for a physical drop; enforce it is unique per DA.
--
-- Dedupe FIRST, non-destructively: keep the earliest row's deposit_ref and NULL the ref on any later
-- duplicates. We never delete a deposit row (that would destroy a financial record) — nulling the ref
-- only removes the conflicting idempotency key so the partial-unique index below can be created.
WITH ranked AS (
    SELECT id,
           row_number() OVER (PARTITION BY da_user_id, deposit_ref ORDER BY created_at, id) AS rn
    FROM cod_cash_deposit
    WHERE deposit_ref IS NOT NULL
)
UPDATE cod_cash_deposit d
SET deposit_ref = NULL
FROM ranked r
WHERE d.id = r.id AND r.rn > 1;

-- Partial unique: null deposit_ref (no idempotency key supplied) is exempt.
CREATE UNIQUE INDEX uq_cod_cash_deposit_da_ref
    ON cod_cash_deposit (da_user_id, deposit_ref)
    WHERE deposit_ref IS NOT NULL;
