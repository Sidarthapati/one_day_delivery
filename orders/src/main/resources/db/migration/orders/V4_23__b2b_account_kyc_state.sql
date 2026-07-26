-- B2B account KYC/verification state machine + business identity captured at provisioning.
-- verification_status: UNVERIFIED → KYC_PENDING → ACTIVE | MANUAL_REVIEW → ACTIVE|REJECTED.
-- is_active stays the hard ship/no-ship gate (derived from verification_status = ACTIVE).
ALTER TABLE b2b_accounts
    ADD COLUMN verification_status VARCHAR(20) NOT NULL DEFAULT 'UNVERIFIED',
    ADD COLUMN business_type       VARCHAR(30),
    ADD COLUMN pan                 VARCHAR(10),
    ADD COLUMN gstin_verified      BOOLEAN,
    ADD COLUMN pan_verified        BOOLEAN,
    ADD COLUMN bank_account_masked VARCHAR(30),
    ADD COLUMN bank_ifsc           VARCHAR(15),
    ADD COLUMN bank_verified       BOOLEAN,
    ADD COLUMN kyc_submitted_at    TIMESTAMPTZ,
    ADD COLUMN activated_at        TIMESTAMPTZ,
    ADD COLUMN rejection_reason    VARCHAR(500);

-- Existing accounts are already live → keep them ACTIVE so the ship gate is unchanged.
UPDATE b2b_accounts
   SET verification_status = 'ACTIVE',
       activated_at = COALESCE(activated_at, now())
 WHERE is_active = true;
