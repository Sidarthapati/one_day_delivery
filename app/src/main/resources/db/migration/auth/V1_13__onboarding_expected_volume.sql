-- Expected monthly parcel volume, self-declared at business onboarding. Drives the small-vs-large
-- merchant split: small merchants auto-approve; large ones (>= the configured threshold) go to the
-- ADMIN queue. Nullable — personal onboardings and pre-existing rows leave it null.
ALTER TABLE onboarding_requests
    ADD COLUMN expected_monthly_orders VARCHAR(20);
