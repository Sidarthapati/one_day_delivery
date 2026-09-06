-- Scope the "one live disposition per DA" guard to the operating date. Previously the partial unique
-- index (and the app-side guard) treated any PENDING/ACTIVE/OVERSTAYED row as live regardless of date,
-- so a stale row from a prior day — an OVERSTAYED break never reconciled, or an un-actioned PENDING
-- day-off — permanently blocked the DA (409 on every later day, and the index rejected a fresh insert).
-- The guard/lookups are now date-scoped and sweep() auto-cancels carry-over rows; the index matches.
DROP INDEX IF EXISTS uq_da_disposition_live_per_da;
CREATE UNIQUE INDEX uq_da_disposition_live_per_da ON da_disposition (da_id, operating_date)
    WHERE status IN ('PENDING', 'ACTIVE', 'OVERSTAYED');
