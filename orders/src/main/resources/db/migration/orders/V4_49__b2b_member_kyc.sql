-- Per-user KYC (Discussion-2 xii): each team member can be KYC'd individually; skipping is allowed, so a
-- member stays UNVERIFIED (a display label) until they verify. Distinct from account-level KYB
-- (b2b_accounts.verification_status), which gates the whole account.
ALTER TABLE b2b_account_member ADD COLUMN IF NOT EXISTS kyc_status VARCHAR(16) NOT NULL DEFAULT 'UNVERIFIED';

-- Owners of an ACTIVE account passed KYB at onboarding, so carry that onto their per-member KYC (they'd
-- otherwise show "unverified" on their own team page). Owners of accounts still in MANUAL_REVIEW / REJECTED
-- / KYC_PENDING never passed, so they stay UNVERIFIED like any other member.
UPDATE b2b_account_member m SET kyc_status = 'VERIFIED'
FROM b2b_accounts a
WHERE m.b2b_account_id = a.id AND m.role = 'OWNER' AND a.verification_status = 'ACTIVE';
