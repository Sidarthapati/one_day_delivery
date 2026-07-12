-- HUB_RETURN meeting-mode states (per-city M6 gate). In cities with no van/meeting-point
-- infrastructure the DA carries pickups back to the hub and collects deliveries there, so the
-- shipment_state enum needs custody states that replace the van ones:
--   RETURNED_TO_HUB        first-mile: DA carried the pickup back to the origin hub (no pickup van)
--   HUB_DELIVERY_ASSIGNED  last-mile:  territory DA assigned; parcel waits at dest hub for their visit
--   COLLECTED_FROM_HUB     last-mile:  DA collected the parcel at the hub — out for delivery
-- PG 12+ allows ADD VALUE inside Flyway's transaction (the values are only added here, not used).
ALTER TYPE shipment_state ADD VALUE IF NOT EXISTS 'RETURNED_TO_HUB';
ALTER TYPE shipment_state ADD VALUE IF NOT EXISTS 'HUB_DELIVERY_ASSIGNED';
ALTER TYPE shipment_state ADD VALUE IF NOT EXISTS 'COLLECTED_FROM_HUB';
