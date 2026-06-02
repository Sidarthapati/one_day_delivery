-- Demo seed: fixed B2B account for sales demo website.
-- UUID is hardcoded in demo/index.html so the demo always books against the same account.
-- Credit limit ₹50,000 with ₹12,000 outstanding — plenty of room for live demo bookings.
INSERT INTO b2b_accounts (
    id,
    account_name,
    billing_email,
    credit_limit_paise,
    outstanding_balance_paise,
    payment_terms_days,
    city_id,
    is_active,
    rate_card_id,
    created_at,
    updated_at
)
VALUES (
    'a1b2c3d4-e5f6-7890-abcd-ef1234567890',
    'Acme Corp (Demo)',
    'demo@acmecorp.example.com',
    5000000,
    1200000,
    30,
    'BLR',
    true,
    'c0000000-0000-0000-0000-000000000001',
    NOW(),
    NOW()
)
ON CONFLICT (id) DO NOTHING;
