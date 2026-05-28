CREATE TABLE h3_pincode_mapping (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    city_id        UUID NOT NULL,
    pincode        VARCHAR(10) NOT NULL,
    hex_id         UUID REFERENCES h3_hex(id),
    is_serviceable BOOLEAN NOT NULL DEFAULT true,
    UNIQUE(city_id, pincode)
);
