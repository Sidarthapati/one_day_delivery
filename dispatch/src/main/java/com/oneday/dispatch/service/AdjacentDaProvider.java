package com.oneday.dispatch.service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Supplies candidate DAs serving tiles adjacent to an overloaded origin tile, for cross-territory
 * spill-over (design §10). M3 exposes per-tile load scores but not tile adjacency yet, so the default
 * implementation returns nothing and cross-territory effectively no-ops (the plan's v1 fallback). A
 * real provider (H3 ring over the origin's h3Index mapped back to DA territories) is a follow-up.
 */
public interface AdjacentDaProvider {

    List<Candidate> candidates(UUID cityId, UUID originTileId, LocalDate date);

    /** A neighbouring DA and the tile of theirs we'd spill into (its load is checked before use). */
    record Candidate(UUID daId, UUID tileId) {}
}
