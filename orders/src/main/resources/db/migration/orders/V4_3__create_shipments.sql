CREATE TABLE shipments (
  id                       UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
  shipment_ref             VARCHAR(30)   NOT NULL UNIQUE,
  customer_type            customer_type NOT NULL,
  delivery_type            delivery_type NOT NULL,
  b2b_account_id           UUID,
  sender_name              VARCHAR(100)  NOT NULL,
  sender_phone             VARCHAR(15)   NOT NULL,
  sender_email             VARCHAR(254),
  origin_address           JSONB         NOT NULL,
  origin_city              VARCHAR(10)   NOT NULL,
  origin_pincode           VARCHAR(10)   NOT NULL,
  dest_address             JSONB         NOT NULL,
  dest_city                VARCHAR(10)   NOT NULL,
  dest_pincode             VARCHAR(10)   NOT NULL,
  receiver_name            VARCHAR(100)  NOT NULL,
  receiver_phone           VARCHAR(15)   NOT NULL,
  receiver_email           VARCHAR(254),
  weight_grams             INTEGER       NOT NULL CHECK (weight_grams BETWEEN 1 AND 70000),
  length_cm                SMALLINT      NOT NULL CHECK (length_cm BETWEEN 1 AND 150),
  width_cm                 SMALLINT      NOT NULL CHECK (width_cm BETWEEN 1 AND 150),
  height_cm                SMALLINT      NOT NULL CHECK (height_cm BETWEEN 1 AND 150),
  volumetric_weight_grams  INTEGER       NOT NULL,
  chargeable_weight_grams  INTEGER       NOT NULL,
  declared_value_paise     BIGINT,
  quoted_price_paise       BIGINT        NOT NULL,
  tax_paise                BIGINT        NOT NULL,
  total_price_paise        BIGINT        NOT NULL,
  final_price_paise        BIGINT,
  rate_card_version        VARCHAR(50)   NOT NULL,
  pickup_type              pickup_type   NOT NULL DEFAULT 'DA_PICKUP',
  drop_type                drop_type     NOT NULL DEFAULT 'DA_DELIVERY',
  state                    shipment_state NOT NULL DEFAULT 'BOOKED',
  sla_commitment_minutes   SMALLINT,
  eta_promised             TIMESTAMPTZ,
  eta_updated              TIMESTAMPTZ,
  assigned_flight_id       UUID,
  origin_tile_id           UUID,
  parcel_id                VARCHAR(30),
  payment_mode             payment_mode,
  payment_id               UUID,
  idempotency_key          VARCHAR(100),
  cancelled_at             TIMESTAMPTZ,
  cancellation_reason      VARCHAR(500),
  archived_at              TIMESTAMPTZ,
  city_id                  VARCHAR(10)   NOT NULL,
  created_at               TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
  updated_at               TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_shipments_state          ON shipments(state);
CREATE INDEX idx_shipments_origin_city    ON shipments(origin_city);
CREATE INDEX idx_shipments_dest_city      ON shipments(dest_city);
CREATE INDEX idx_shipments_b2b_account    ON shipments(b2b_account_id)    WHERE b2b_account_id IS NOT NULL;
CREATE INDEX idx_shipments_parcel_id      ON shipments(parcel_id)         WHERE parcel_id IS NOT NULL;
CREATE INDEX idx_shipments_receiver_phone ON shipments(receiver_phone);
CREATE INDEX idx_shipments_created_at     ON shipments(created_at);
CREATE INDEX idx_shipments_city_state     ON shipments(city_id, state);
CREATE INDEX idx_shipments_flight_id      ON shipments(assigned_flight_id) WHERE assigned_flight_id IS NOT NULL;
CREATE INDEX idx_shipments_archived       ON shipments(archived_at)        WHERE archived_at IS NOT NULL;

CREATE TRIGGER trg_shipments_updated_at
  BEFORE UPDATE ON shipments
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
