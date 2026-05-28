ALTER TABLE da_tile_assignment
    DROP CONSTRAINT da_tile_assignment_da_id_tile_id_valid_date_key;

ALTER TABLE da_tile_assignment
    ADD CONSTRAINT da_tile_assignment_proposal_da_tile_date_key
    UNIQUE (proposal_id, da_id, tile_id, valid_date);
