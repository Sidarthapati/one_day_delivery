-- M4 Order → N shipments: denormalise the parent order onto every dispatch task so the DA app can
-- collapse same-order/same-location pickups (or drops) into a single stop card, without dispatch having
-- to query the orders module. Bare UUID + display ref, cross-module convention (no FK). Both nullable —
-- legacy tasks and shipments booked before the Order abstraction carry null (rendered as singleton stops).
ALTER TABLE dispatch_queue  ADD COLUMN order_id UUID;
ALTER TABLE dispatch_queue  ADD COLUMN order_ref VARCHAR(30);
CREATE INDEX idx_dispatch_queue_order_id ON dispatch_queue (order_id);

-- Carry the same order through the hold-then-release path so scheduled/off-hours bulk pickups group too.
ALTER TABLE scheduled_pickup ADD COLUMN order_id UUID;
ALTER TABLE scheduled_pickup ADD COLUMN order_ref VARCHAR(30);

-- ...and through the defer-then-retry path so a bulk order that briefly defers still groups on assignment.
ALTER TABLE deferred_dispatch ADD COLUMN order_id UUID;
ALTER TABLE deferred_dispatch ADD COLUMN order_ref VARCHAR(30);
