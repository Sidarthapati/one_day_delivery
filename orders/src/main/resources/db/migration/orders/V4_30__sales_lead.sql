-- Public "Talk to sales" lead capture (P4). A prospect submits the form on the marketing site;
-- it lands here and shows up in the admin console's lead queue. No auth on the write path.
CREATE TABLE sales_lead (
    id             UUID PRIMARY KEY,
    name           VARCHAR(120) NOT NULL,
    company        VARCHAR(200),
    email          VARCHAR(254) NOT NULL,
    phone          VARCHAR(20),
    monthly_volume VARCHAR(20),
    message        VARCHAR(2000),
    -- NEW → CONTACTED → WON | LOST
    status         VARCHAR(20)  NOT NULL DEFAULT 'NEW',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_sales_lead_status ON sales_lead (status, created_at DESC);
