package com.oneday.routing.domain;

/** Lifecycle of a route plan (§10). Append-only: a new revision supersedes; rows never mutate. */
public enum RoutePlanStatus {
    PROPOSED,
    APPROVED,
    SUPERSEDED,
    REJECTED
}
