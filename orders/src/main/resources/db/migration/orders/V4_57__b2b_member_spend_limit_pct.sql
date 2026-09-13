-- Discussion-4 (M1): a member's monthly budget can now be a PERCENTAGE of the account's available
-- credit, as an alternative to the fixed spend_limit_paise from D3 (vi). A member is exactly one of:
-- unlimited (both NULL), fixed (spend_limit_paise set), or percentage (spend_limit_pct set). Mutual
-- exclusivity is enforced in the service. The % is of (credit_limit - outstanding) at booking time.
ALTER TABLE b2b_account_member ADD COLUMN spend_limit_pct INTEGER;  -- matches the entity's Integer field

COMMENT ON COLUMN b2b_account_member.spend_limit_pct IS
    'Monthly cap as a percent (1..100) of the account''s available credit; NULL unless this is a % cap.';
