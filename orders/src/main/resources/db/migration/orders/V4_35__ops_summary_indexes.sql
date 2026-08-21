-- Ops monitoring dashboard: composite indexes backing the city-scoped summary query
--   SELECT state, COUNT(*) FROM shipments WHERE origin_city = ? OR dest_city = ? GROUP BY state
-- The all-cities variant (GROUP BY state, no filter) is already served by idx_shipments_state
-- from V4_3; only these two (city, state) composites were missing. They let Postgres aggregate
-- each arm of the OR with an index-only scan instead of touching the heap.
--
-- Plain (non-CONCURRENT) CREATE: Flyway runs each migration in a transaction, and
-- CREATE INDEX CONCURRENTLY cannot run inside one. The brief lock at deploy is acceptable.
CREATE INDEX IF NOT EXISTS idx_shipments_origin_city_state ON shipments (origin_city, state);
CREATE INDEX IF NOT EXISTS idx_shipments_dest_city_state   ON shipments (dest_city, state);
