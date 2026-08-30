-- Redelivery: a deferred delivery can be parked for a specific next-day shift (the receiver's chosen
-- Shift 1 / Shift 2, or a failure retry). NULL = eligible in any shift (the pre-existing behaviour, so
-- every existing PICKUP/DELIVERY deferral is unaffected). The retry job only re-assigns a row whose
-- target_shift is NULL or matches the currently-active shift.
ALTER TABLE deferred_dispatch
    ADD COLUMN target_shift VARCHAR(20);   -- SHIFT_1 | SHIFT_2 | NULL
