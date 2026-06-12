-- M4: one line in a cart = one complete, independent shipment draft (its own pickup + drop +
-- parcel). serviceability/pricing are cached for display; checkout re-validates and books.
CREATE TABLE cart_item (
  id                   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
  cart_id              UUID         NOT NULL REFERENCES cart(id) ON DELETE CASCADE,
  source               VARCHAR(8)   NOT NULL DEFAULT 'MANUAL',  -- MANUAL | EXCEL
  excel_row_num        INT,
  -- pickup
  sender_name          VARCHAR(100) NOT NULL,
  sender_phone         VARCHAR(15)  NOT NULL,
  sender_email         VARCHAR(254),
  origin_address       JSONB        NOT NULL,
  origin_city          VARCHAR(100) NOT NULL,
  origin_pincode       VARCHAR(10)  NOT NULL,
  -- drop
  receiver_name        VARCHAR(100) NOT NULL,
  receiver_phone       VARCHAR(15)  NOT NULL,
  receiver_email       VARCHAR(254),
  dest_address         JSONB        NOT NULL,
  dest_city            VARCHAR(100) NOT NULL,
  dest_pincode         VARCHAR(10)  NOT NULL,
  -- parcel
  weight_grams         INT          NOT NULL,
  length_cm            SMALLINT     NOT NULL,
  width_cm             SMALLINT     NOT NULL,
  height_cm            SMALLINT     NOT NULL,
  declared_value_paise BIGINT,
  pickup_type          VARCHAR(16)  NOT NULL,
  drop_type            VARCHAR(16)  NOT NULL,
  -- cached compute (for cart display; re-validated at checkout)
  origin_tile_id       UUID,
  dest_tile_id         UUID,
  delivery_type        VARCHAR(16),
  quoted_total_paise   BIGINT,
  validation_status    VARCHAR(8)   NOT NULL DEFAULT 'VALID',  -- VALID | STALE
  booked_shipment_ref  VARCHAR(64),
  created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_cart_item_cart ON cart_item (cart_id);

CREATE TRIGGER trg_cart_item_updated_at
  BEFORE UPDATE ON cart_item
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
