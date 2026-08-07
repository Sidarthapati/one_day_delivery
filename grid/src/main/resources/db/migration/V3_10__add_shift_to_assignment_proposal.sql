-- Shift dimension on the nightly plan. The territory plan now runs once per shift (SHIFT_1 / SHIFT_2),
-- so a single city + date can carry two APPROVED proposals. `shift` scopes the approve-supersede so
-- approving one shift's plan doesn't clobber the other's. NULL = shift-agnostic (intraday
-- overrides/shares and legacy rows). da_hex_assignment needs no shift column: a DA belongs to exactly
-- one shift, so its (da_id, hex_id, valid_date) rows never collide across shifts.
ALTER TABLE assignment_proposal ADD COLUMN shift VARCHAR(10);

-- Backfill existing nightly proposals to SHIFT_1 (the historical single-shift plan).
UPDATE assignment_proposal SET shift = 'SHIFT_1'
 WHERE shift IS NULL AND proposal_type = 'NIGHTLY';
