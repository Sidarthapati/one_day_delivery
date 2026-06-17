package com.oneday.grid.domain;

public enum AssignmentStatus {
    PROPOSED, APPROVED,
    /**
     * @deprecated Collapsed into {@link #APPROVED}. The system never implemented an
     * APPROVED→ACTIVE go-live flip, so {@code validDate} (which day) + {@link #SUPERSEDED}
     * (which assignment wins) already carried everything this state meant. M6 nightly
     * planning, the grid map view, the intraday monitor, override, tile-share and the
     * auto-fallback all read/write {@code APPROVED} now. Migration V3_9 converts existing
     * rows. Retained only so any legacy {@code 'ACTIVE'} row still deserializes; do not
     * write it.
     */
    @Deprecated
    ACTIVE,
    SUPERSEDED
}
