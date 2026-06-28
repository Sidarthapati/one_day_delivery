package com.oneday.hub.domain;

/**
 * What a destination delivery bag is keyed by (§8.1, M7-D-012). The ladder picks the most specific
 * one that resolves: a route bag when a van runs the territory, else a DA-territory bag the DA
 * hub-collects, else (pool overflow) an M3-zone bag grouping adjacent territories.
 */
public enum BagKind {
    ROUTE,         // keyed by the M6 van loop — a van loads the whole bag (preferred)
    DA_TERRITORY,  // keyed by the DA territory — the DA hub-collects (no van)
    ZONE           // keyed by an M3 zone — adjacent territories grouped to fit the stand pool (Q13)
}
