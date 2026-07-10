-- M4: per-user saved address book (HOME/OFFICE/OTHER), reused for pickup & drop in
-- the booking + cart flows. ADMIN manages these via direct DB access; the API is
-- scoped to customer roles (C2C/B2C/B2B).
CREATE TABLE saved_address (
  id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
  -- Plain UUID, no FK to users: orders is a separate module from auth and intentionally
  -- does not couple its schema to the users table (matches shipments.booked_by_user_id).
  user_id               UUID         NOT NULL,
  label                 VARCHAR(10)  NOT NULL,            -- HOME | OFFICE | OTHER
  save_as               VARCHAR(100),                     -- optional free text ("Mom's place")
  contact_name          VARCHAR(100),
  contact_phone         VARCHAR(15),                      -- +91XXXXXXXXXX
  -- address (mirrors the embedded Address; flattened so it is queryable)
  house_floor           VARCHAR(200),
  building_street       VARCHAR(200),
  area_locality         VARCHAR(300),
  line1                 VARCHAR(200) NOT NULL,
  line2                 VARCHAR(200),
  city                  VARCHAR(100) NOT NULL,
  pincode               VARCHAR(10)  NOT NULL,
  state                 VARCHAR(100) NOT NULL,
  landmark              VARCHAR(200),
  latitude              DOUBLE PRECISION,
  longitude             DOUBLE PRECISION,
  delivery_instructions VARCHAR(500),
  created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_saved_address_user ON saved_address(user_id);

CREATE TRIGGER trg_saved_address_updated_at
  BEFORE UPDATE ON saved_address
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
