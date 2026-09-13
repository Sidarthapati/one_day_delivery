-- Documents uploaded by an onboarding candidate (Aadhaar/PAN/DL/Voter/photo). The bytes live in R2;
-- this table holds the object key + a per-document verification status. One row per (candidate, doc type)
-- — re-uploading a type replaces its key (see OnboardingDocumentServiceImpl.submit).
CREATE TABLE da_onboarding_document (
    id            UUID PRIMARY KEY,
    candidate_id  UUID NOT NULL REFERENCES da_onboarding_candidate(id),
    doc_type      VARCHAR(20) NOT NULL
        CHECK (doc_type IN ('AADHAAR_FRONT','AADHAAR_BACK','PAN','DRIVING_LICENSE','VOTER','PHOTO')),
    object_key    TEXT NOT NULL,
    status        VARCHAR(10) NOT NULL DEFAULT 'UPLOADED'
        CHECK (status IN ('UPLOADED','VERIFIED','REJECTED')),
    uploaded_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (candidate_id, doc_type)
);

CREATE INDEX idx_da_onboarding_document_candidate ON da_onboarding_document (candidate_id);
