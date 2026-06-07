-- Links a shipment to the M1 user who placed the booking. Powers the customer "my shipments"
-- view (GET /api/v1/shipments/mine) so a customer sees their full booking history across
-- sessions, not just the bookings made in the current browser session.
-- Nullable: pre-existing rows booked before this column existed are left unattributed.
ALTER TABLE shipments ADD COLUMN booked_by_user_id UUID;

-- The view lists a single user's shipments newest-first, so index on (user, recency).
CREATE INDEX idx_shipments_booked_by_user
    ON shipments (booked_by_user_id, created_at DESC);
