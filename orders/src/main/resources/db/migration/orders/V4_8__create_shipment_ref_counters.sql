CREATE TABLE shipment_ref_counters (
  city_code VARCHAR(10) NOT NULL,
  date_key  DATE        NOT NULL,
  next_val  INTEGER     NOT NULL DEFAULT 1,
  PRIMARY KEY (city_code, date_key)
);
