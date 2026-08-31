package com.oneday.dispatch.repository;

import java.util.UUID;

/**
 * Per-DA rollup of location-trust signals over a window (Spring Data projection). Backs the ops
 * integrity console: how many fixes a DA sent that day and how many were untrustworthy.
 */
public interface DaPingIntegrityAggregate {
    UUID getDaId();
    long getTotal();
    long getFlagged();       // fixes with risk_score > 0
    Integer getMaxRisk();    // null if no rows (shouldn't happen inside a group)
    long getMockedCount();
    long getVelocityCount();
    long getSkewCount();
}
