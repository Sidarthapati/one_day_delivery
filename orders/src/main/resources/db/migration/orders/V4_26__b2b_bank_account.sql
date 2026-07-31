-- Merchant payout bank account for COD remittance.
-- V4_23 added skeletal bank_account_masked/bank_ifsc/bank_verified but nothing captured or
-- verified them. This completes the payout account: the full account number (needed to actually
-- transfer), the beneficiary name returned by the bank on penny-drop verification, the bank name,
-- a verification state machine, the provider's penny-drop reference, and COD notification emails.
--
-- bank_verification_state: NONE → PENDING (penny-drop in flight) → VERIFIED (name matched) |
--                          MANUAL_VERIFIED (finance eyeballed, no provider) | FAILED.
-- Only a VERIFIED / MANUAL_VERIFIED account may receive a COD payout.
ALTER TABLE b2b_accounts
    ADD COLUMN bank_account_number     VARCHAR(30),
    ADD COLUMN bank_beneficiary_name   VARCHAR(200),
    ADD COLUMN bank_name               VARCHAR(120),
    ADD COLUMN bank_verification_state VARCHAR(20) NOT NULL DEFAULT 'NONE',
    ADD COLUMN bank_penny_drop_ref     VARCHAR(80),
    ADD COLUMN bank_verified_at        TIMESTAMPTZ,
    ADD COLUMN cod_notify_emails       VARCHAR(500);

-- Back-fill the state from the old boolean so existing rows are consistent.
UPDATE b2b_accounts
   SET bank_verification_state = 'VERIFIED'
 WHERE bank_verified = true;

-- Seed the demo B2B account (V4_13) with a verified payout account so the admin payout worklist
-- and the vendor remittance ledger work end-to-end in the demo without a provider.
UPDATE b2b_accounts
   SET bank_account_number     = '50100123453210',
       bank_account_masked     = 'XXXXXX3210',
       bank_ifsc               = 'HDFC0000123',
       bank_beneficiary_name   = 'Demo B2B Test Co',
       bank_name               = 'HDFC Bank',
       bank_verification_state = 'MANUAL_VERIFIED',
       bank_verified           = true,
       bank_verified_at        = now()
 WHERE id IN ('a1b2c3d4-e5f6-7890-abcd-ef1234567890',
              'e235e22f-2d61-4a8e-924c-166d7f735bd5');
