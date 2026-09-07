-- Discussion-3 (vi): per-member spend budget. A B2B account owner can cap how much an individual
-- member spends per calendar month, independent of the shared account credit limit. NULL = no cap
-- (owners, and members left uncapped). Enforced at booking beside the account-credit check; spend is
-- summed on demand from shipments.booked_by_user_id, so there is no counter to reset each month.
ALTER TABLE b2b_account_member ADD COLUMN spend_limit_paise BIGINT;

COMMENT ON COLUMN b2b_account_member.spend_limit_paise IS
    'Per-member spend cap in paise for the current calendar month; NULL = unlimited.';
