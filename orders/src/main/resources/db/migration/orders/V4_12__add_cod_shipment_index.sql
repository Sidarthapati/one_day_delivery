-- PR #11: partial index for COD shipment queries.
-- M5 dispatch and future COD collection workflows filter on payment_mode = 'COD'
-- combined with city_id and state. The existing idx_shipments_city_state covers
-- the full population; this partial index is smaller and faster for COD-only scans.

CREATE INDEX idx_shipments_cod
    ON shipments(city_id, state)
    WHERE payment_mode = 'COD';
