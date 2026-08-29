-- Per-user KYC (Discussion-2 xii): each team member can be KYC'd individually; skipping is allowed, so a
-- member stays UNVERIFIED (a display label) until they verify. Distinct from account-level KYB
-- (b2b_accounts.verification_status), which gates the whole account.
ALTER TABLE b2b_account_member ADD COLUMN kyc_status VARCHAR(16) NOT NULL DEFAULT 'UNVERIFIED';

-- The account owner already completed KYB at onboarding to activate the account, so treat them as verified
-- (they'd otherwise show "unverified" on their own team page).
UPDATE b2b_account_member SET kyc_status = 'VERIFIED' WHERE role = 'OWNER';
