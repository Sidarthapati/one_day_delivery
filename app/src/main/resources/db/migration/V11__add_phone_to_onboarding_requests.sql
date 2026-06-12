-- Capture the applicant's phone during B2C/B2B onboarding so the approved user has an account
-- phone (the locked sender contact on retail bookings, same as C2C). Nullable for rows created
-- before this column existed.
ALTER TABLE onboarding_requests ADD COLUMN phone VARCHAR(15);
