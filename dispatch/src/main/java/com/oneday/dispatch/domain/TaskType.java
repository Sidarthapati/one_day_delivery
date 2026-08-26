package com.oneday.dispatch.domain;

/** What a dispatch task is — a first-mile pickup, a last-mile delivery, or a custody handoff. */
public enum TaskType {
    PICKUP,
    DELIVERY,
    // Midday-absence handoff: the covering DA physically collects an in-custody parcel from the
    // absent DA (task_lat/lon = collect location, collect_from_da_id = the absent DA) before its
    // onward leg resumes. Recorded via an M8 custody scan on completion.
    CUSTODY_COLLECT
}
