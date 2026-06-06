-- B2B account ownership: links a b2b_account to the M1 user authorized to book against its
-- credit. Enforced at booking (caller's userId must equal owner_user_id). Nullable so legacy
-- rows without an owner are left unrestricted until backfilled.
ALTER TABLE b2b_accounts ADD COLUMN owner_user_id UUID;

-- The two demo accounts are owned by the synthetic demo principal (DemoAuthFilter.DEMO_USER_ID)
-- so the sales-demo booking flow passes the ownership check.
UPDATE b2b_accounts SET owner_user_id = '00000000-0000-0000-0000-000000000001'
WHERE id IN ('a1b2c3d4-e5f6-7890-abcd-ef1234567890', 'b2b0dead-fade-0000-0000-000000000001');
