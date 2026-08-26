-- Midday DA-absence handoff (M5), completion half. A CUSTODY_COLLECT task's task_lat/task_lon is the
-- COLLECT point (where the covering DA meets the absent DA). To resume the parcel's journey once
-- collected, the row also carries the original leg (PICKUP/DELIVERY) and its destination — used to
-- spawn the onward task, in-hand (IN_PROGRESS), on the covering DA. Nullable; set only on CUSTODY_COLLECT.
ALTER TABLE dispatch_queue ADD COLUMN onward_task_type VARCHAR(20);
ALTER TABLE dispatch_queue ADD COLUMN onward_task_lat  DOUBLE PRECISION;
ALTER TABLE dispatch_queue ADD COLUMN onward_task_lon  DOUBLE PRECISION;
