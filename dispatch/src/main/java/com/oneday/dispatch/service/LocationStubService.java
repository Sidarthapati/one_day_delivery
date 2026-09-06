package com.oneday.dispatch.service;

import com.oneday.dispatch.domain.DispatchQueue;

/**
 * Maintains the DA location-stub "visits" (time-at-location metric). All methods run inside the caller's
 * transaction ({@code MANDATORY}) and are invoked under the DA lock the dispatch/task lifecycle already
 * holds, so a stub's rollups are never raced within a DA.
 */
public interface LocationStubService {

    /**
     * Bind a newly-created queue task to its location visit: join the OPEN stub for that (DA, day,
     * location) or open a new one, stamp {@code row.stubId}, and grow the ticket's task count. Call this
     * on a fresh {@link DispatchQueue} <b>before</b> it is persisted.
     */
    void attach(DispatchQueue row);

    /** The DA tapped "Mark arrived" at the task's stop — start the visit's tap-dwell clock. */
    void onArrived(DispatchQueue task);

    /**
     * The task reached a terminal status (COMPLETED / FAILED / CANCELLED). Advances the visit's dwell
     * clock + processed/failed counts, and CLOSES the stub once no task in it is still open — which is
     * what makes a later return to the same location open a fresh visit.
     */
    void onTerminal(DispatchQueue task);
}
