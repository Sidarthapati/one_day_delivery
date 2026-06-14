-- Drop-and-flag (M6): meeting vertices the solve deferred because their solo hub round-trip exceeds
-- the cycle target. Stored as a JSON array of vertex UUIDs so the planning console can render covered
-- vs deferred vertices on the map. Nullable; null/empty = nothing deferred.
ALTER TABLE route_plan ADD COLUMN IF NOT EXISTS deferred_vertex_ids text;
