-- Order back-reference on the per-shipment SLA row. The ShipmentCreatedEvent already carries the
-- parent order (Order → N shipments) but M10 was discarding it; persist it so the control tower can
-- correlate the N parcels of one breaching booking into a single merchant call. Null for legacy rows.
ALTER TABLE sla_shipment ADD COLUMN order_id  UUID;
ALTER TABLE sla_shipment ADD COLUMN order_ref VARCHAR(30);

-- The order-correlation cluster groups the live at-risk set by order_ref; index over the open set.
CREATE INDEX idx_sla_shipment_open_order ON sla_shipment (order_ref) WHERE closed_at IS NULL;
