-- DA identity / HR profile — one row per DELIVERY_ASSOCIATE user. The auth `users` row still owns
-- email, phone, city_id and active; this table adds the HR fields, the contract window and the shift.
-- Aadhaar/PAN and the PAN document are optional for the pilot (binary upload deferred; pan_doc_url
-- holds a text pointer only for now). contract_end_date NULL = active / open-ended.
CREATE TABLE da_profile (
    user_id             UUID PRIMARY KEY REFERENCES users(id),
    first_name          VARCHAR(80)  NOT NULL,
    last_name           VARCHAR(80)  NOT NULL,
    aadhaar             VARCHAR(20),
    pan                 VARCHAR(15),
    pan_doc_url         TEXT,
    contract_start_date DATE NOT NULL,
    contract_end_date   DATE,
    shift               VARCHAR(10) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_da_profile_shift ON da_profile (shift);
