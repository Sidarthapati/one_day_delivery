package com.oneday.dispatch.domain;

/**
 * Lifecycle of a {@link DaLocationStub} visit. OPEN while the DA still has tasks to work at the location;
 * CLOSED once every task in the visit is terminal. A later return to the same location opens a NEW stub —
 * closing is what makes two visits to one doorstep separate tickets (see the partial-unique index).
 */
public enum StubStatus {
    OPEN,
    CLOSED
}
