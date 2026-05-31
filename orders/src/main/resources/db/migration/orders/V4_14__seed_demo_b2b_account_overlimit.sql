-- Demo seed: second B2B account for the "credit limit exceeded" demo button.
-- Remaining credit is ₹5 (500,000 - 499,500 paise), which is below the minimum
-- booking cost of ~₹50, so any booking attempt returns 402.
-- UUID is hardcoded in demo/index.html — sendB2bOverLimit uses this account.
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
    'b2b0dead-fade-0000-0000-000000000001',
    'QuickShip Ltd (Demo — Near Limit)',
    'demo@quickship.example.com',
    500000,
    499500,
    30,
    'BLR',
    true,
    'c0000000-0000-0000-0000-000000000001',
    NOW(),
    NOW()
)
ON CONFLICT (id) DO NOTHING;
