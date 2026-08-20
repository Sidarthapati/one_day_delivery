CREATE TABLE onboarding_requests (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email            VARCHAR(255) NOT NULL UNIQUE,
    name             VARCHAR(255) NOT NULL,
    requested_role   VARCHAR(50)  NOT NULL CHECK (requested_role IN ('B2B_USER', 'B2C_CUSTOMER')),
    password_hash    VARCHAR(255) NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    rejection_reason TEXT,
    reviewed_by      UUID REFERENCES users(id),
    reviewed_at      TIMESTAMP,
    created_at       TIMESTAMP NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_onboarding_requests_status ON onboarding_requests(status);
CREATE INDEX idx_onboarding_requests_email  ON onboarding_requests(email);
