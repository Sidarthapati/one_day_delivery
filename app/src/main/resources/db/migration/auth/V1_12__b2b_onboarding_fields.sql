-- B2B business onboarding: capture the business identity + KYC verdicts on the onboarding request.
-- All nullable — set only for a business (B2B_USER) onboarding; personal onboardings leave them null.
ALTER TABLE onboarding_requests
    ADD COLUMN company_name     VARCHAR(200),
    ADD COLUMN business_type    VARCHAR(30),
    ADD COLUMN gstin            VARCHAR(15),
    ADD COLUMN pan              VARCHAR(10),
    ADD COLUMN billing_email    VARCHAR(254),
    ADD COLUMN city_id          VARCHAR(10),
    ADD COLUMN gstin_verified   BOOLEAN,
    ADD COLUMN pan_verified     BOOLEAN,
    ADD COLUMN gstin_legal_name VARCHAR(200),
    ADD COLUMN kyc_message      VARCHAR(500);
