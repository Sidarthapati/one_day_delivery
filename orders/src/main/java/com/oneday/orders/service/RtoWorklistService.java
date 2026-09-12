package com.oneday.orders.service;

import com.oneday.orders.dto.RtoWorklistItem;

import java.util.List;
import java.util.UUID;

/**
 * The hub RTO worklist (feature iii). One item per return, created when the return child is minted;
 * it tells the return hub (origin hub for a same-city return, dest hub for a reverse-lane return) to
 * physically turn the parcel around. Closed when the child is sorted (leaves the hub) or a worker
 * marks it done.
 */
public interface RtoWorklistService {

    /** Record an OPEN work item for a freshly-minted return child. */
    void record(String originalRef, String childRef, String returnHubCity, String lane, boolean needsBagPull);

    /** Auto-close the item for a return child once it has been sorted (left the hub). No-op if none/closed. */
    void closeForChild(String childRef, String doneBy);

    /** Open items for a hub's city; {@code null} city = all cities (ADMIN). */
    List<RtoWorklistItem> listOpen(String cityScope);

    /**
     * Mark an item done (a worker actioned it). {@code cityScope} non-null restricts to that hub's
     * city (else the item is treated as not found — mirrors the cancel guards).
     */
    void markDone(UUID id, String cityScope, String userId);
}
