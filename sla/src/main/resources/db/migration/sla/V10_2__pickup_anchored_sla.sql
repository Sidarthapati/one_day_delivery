-- Reverse of M10-D-006: the SLA clock now starts at pickup-complete, not at booking. Until pickup the
-- shipment has no running target, so these become nullable (set on PICKUP_COMPLETED). booked_at stays
-- for reference. A held/scheduled order therefore burns zero SLA while it waits for its slot.
ALTER TABLE sla_shipment ALTER COLUMN internal_target_at DROP NOT NULL;
ALTER TABLE sla_shipment ALTER COLUMN public_promise_at  DROP NOT NULL;
