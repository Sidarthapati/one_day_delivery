-- Scheduled pickup window (2-hour slot). Both nullable: null = ASAP (assign as soon as feasible /
-- at the next operating window). When set, M5 holds the order out of the DA queue until ~60 min
-- before scheduled_pickup_start, so an off-hours booking doesn't sit in the queue accruing age.
ALTER TABLE shipments
    ADD COLUMN scheduled_pickup_start TIMESTAMPTZ,
    ADD COLUMN scheduled_pickup_end   TIMESTAMPTZ;
