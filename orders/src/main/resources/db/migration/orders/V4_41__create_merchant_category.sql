-- M4: per-merchant section categories. A B2B merchant defines their own categories
-- ("Electronics", "Apparel", …) and tags each shipment with one, for their own filtering/reporting.
-- Account-scoped: the API only ever reads/writes rows for the caller's own b2b_account_id.
CREATE TABLE merchant_category (
  id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  -- Plain UUID, no FK to b2b_accounts: orders keeps FKs implicit cross-table by convention
  -- (matches shipments.b2b_account_id / saved_address.user_id).
  b2b_account_id UUID        NOT NULL,
  name           VARCHAR(60) NOT NULL,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- One category name per merchant (case-insensitive) — no duplicate "Electronics"/"electronics".
CREATE UNIQUE INDEX ux_merchant_category_account_name ON merchant_category (b2b_account_id, lower(name));

CREATE TRIGGER trg_merchant_category_updated_at
  BEFORE UPDATE ON merchant_category
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Tag a shipment with one of its merchant's categories (null = untagged). FK-by-convention to
-- merchant_category; set at booking time, so immutable like the other booking facts.
ALTER TABLE shipments ADD COLUMN category_id UUID;
CREATE INDEX idx_shipments_category ON shipments (b2b_account_id, category_id);
