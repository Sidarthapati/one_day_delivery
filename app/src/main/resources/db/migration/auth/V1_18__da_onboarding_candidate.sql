-- Self-service DA onboarding — a candidate record that a person fills in via an invite link, then a
-- station manager / admin approves. On approval the candidate is provisioned into a real `users` row +
-- `da_profile` (see DaOnboardingServiceImpl), so this table is the pre-hire funnel, not the HR record.
-- Documents (S2) and per-check BGV (S3) get their own tables later; the agreement/training acknowledgement
-- columns are added here (nullable) so those slices need no further migration.
CREATE TABLE da_onboarding_candidate (
    id                     UUID PRIMARY KEY,
    invite_token           VARCHAR(64) NOT NULL UNIQUE,
    status                 VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT','SUBMITTED','BGV_IN_PROGRESS','BGV_CLEAR','BGV_INSUFFICIENT',
                          'PENDING_APPROVAL','APPROVED','ONBOARDED','REJECTED')),

    -- Personal
    first_name             VARCHAR(80),
    last_name              VARCHAR(80),
    email                  VARCHAR(254) NOT NULL UNIQUE,
    phone                  VARCHAR(20),
    dob                    DATE,
    aadhaar                VARCHAR(20),
    pan                    VARCHAR(15),
    driving_license        VARCHAR(30),

    -- Location / assignment (no station entity yet — city scope + a free-text branch label)
    city_id                VARCHAR(10),
    station_label          VARCHAR(120),
    shift                  VARCHAR(10),

    -- Bank
    bank_account_number    VARCHAR(30),
    ifsc                   VARCHAR(15),
    account_holder_name    VARCHAR(120),

    -- Agreement + training (click-to-accept; populated in S4)
    agreement_accepted_at  TIMESTAMPTZ,
    agreement_doc_key      TEXT,
    training_ack_at        TIMESTAMPTZ,

    -- Funnel bookkeeping
    submitted_at           TIMESTAMPTZ,
    approved_by            UUID REFERENCES users(id),
    approved_at            TIMESTAMPTZ,
    rejection_reason       VARCHAR(500),
    provisioned_user_id    UUID REFERENCES users(id),
    employee_id            VARCHAR(40),

    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_da_onboarding_candidate_status ON da_onboarding_candidate (status);

-- Human-friendly, sequential employee id assigned at provisioning (e.g. GD-DEL-1001).
CREATE SEQUENCE da_employee_id_seq START 1001;

-- The real HR record gains the employee id set when a candidate is onboarded.
ALTER TABLE da_profile ADD COLUMN employee_id VARCHAR(40) UNIQUE;
