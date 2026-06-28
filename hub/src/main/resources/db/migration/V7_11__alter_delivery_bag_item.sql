-- M7 §14.3 (M7-D-012) — wire delivery_bag_item to its parent delivery_bag and record the route
-- resolution that placed it there. PR #1 created the table with per-parcel staging only; PR #2 adds
-- the membership FK + the ladder outputs (da_territory_id, route_plan_id). loop_hint stays as a
-- legacy column; the authoritative loop now lives on the parent delivery_bag.
ALTER TABLE delivery_bag_item
    ADD COLUMN delivery_bag_id UUID REFERENCES delivery_bag(id),
    ADD COLUMN da_territory_id UUID,
    ADD COLUMN route_plan_id   UUID;

CREATE INDEX idx_delivery_bag_item_bag ON delivery_bag_item (delivery_bag_id);
