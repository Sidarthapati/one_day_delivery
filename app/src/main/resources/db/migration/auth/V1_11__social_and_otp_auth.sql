-- Google (OIDC) + phone-OTP sign-in. Existing local email/password users are unaffected.

-- Social users have no password; OTP-only users have no email.
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;
ALTER TABLE users ALTER COLUMN email DROP NOT NULL;

-- How the account authenticates. Existing rows are local email/password.
ALTER TABLE users ADD COLUMN auth_provider VARCHAR(20) NOT NULL DEFAULT 'LOCAL';
-- Stable Google account id (the OIDC "sub"); survives the user changing their Google email.
ALTER TABLE users ADD COLUMN provider_subject VARCHAR(255);

-- Phone is the identity for OTP users, the Google sub for Google users → unique when present.
-- Partial indexes: multiple NULLs are allowed (staff / local users without a phone).
CREATE UNIQUE INDEX ux_users_phone            ON users(phone)            WHERE phone            IS NOT NULL;
CREATE UNIQUE INDEX ux_users_provider_subject ON users(provider_subject) WHERE provider_subject IS NOT NULL;

-- Short-lived, hashed, single-use phone OTP challenges.
CREATE TABLE auth_otp (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    phone      VARCHAR(15)  NOT NULL,
    otp_hash   VARCHAR(255) NOT NULL,
    expires_at TIMESTAMP    NOT NULL,
    attempts   SMALLINT     NOT NULL DEFAULT 0,
    consumed   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX idx_auth_otp_phone ON auth_otp(phone);
