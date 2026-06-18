-- One manifest per (van, loop, day) so get-or-create is race-safe; the per-parcel binder locks this row
-- (SELECT FOR UPDATE) before counting capacity, so concurrent binds can't oversubscribe a loop.
ALTER TABLE van_manifest
    ADD CONSTRAINT uq_van_manifest_van_loop_date UNIQUE (van_id, loop_index, valid_date);
