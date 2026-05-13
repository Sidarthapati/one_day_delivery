CREATE TABLE pincode_mapping (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    city_id        UUID NOT NULL,
    pincode        VARCHAR(10) NOT NULL,
    tile_id        UUID REFERENCES tile(id),
    is_serviceable BOOLEAN NOT NULL DEFAULT true,
    UNIQUE(city_id, pincode)
);
