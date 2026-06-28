package com.oneday.hub.domain;

/** Dock reconciliation mismatch kinds (§6, C13). */
public enum DiscrepancyType {
    SHORTFALL,  // expected on the van manifest but not scanned in
    SURPLUS,    // scanned in but not expected
    MISSORT     // arrived at the wrong hub
}
