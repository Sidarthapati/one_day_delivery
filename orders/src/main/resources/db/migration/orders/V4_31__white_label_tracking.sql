-- White-label shipment tracking (P4). A B2B merchant can brand the public tracking page their
-- recipients see, and each B2B shipment gets a public, unguessable tracking token so the merchant
-- can share a link without exposing our authenticated APIs.
ALTER TABLE b2b_accounts
    ADD COLUMN brand_name     VARCHAR(120),
    ADD COLUMN brand_logo_url VARCHAR(500),
    ADD COLUMN brand_color    VARCHAR(20),
    ADD COLUMN support_email  VARCHAR(254),
    ADD COLUMN support_phone  VARCHAR(20);

ALTER TABLE shipments
    ADD COLUMN track_token VARCHAR(40);

-- Back-fill tokens for existing B2B shipments so their links work immediately.
UPDATE shipments
   SET track_token = replace(gen_random_uuid()::text, '-', '')
 WHERE customer_type = 'B2B' AND track_token IS NULL;

CREATE UNIQUE INDEX idx_shipments_track_token ON shipments (track_token) WHERE track_token IS NOT NULL;
