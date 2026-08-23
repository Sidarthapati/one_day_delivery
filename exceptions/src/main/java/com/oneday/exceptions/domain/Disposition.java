package com.oneday.exceptions.domain;

/** The outcome axis the ops rollups group by — "how is this parcel going to end up?" */
public enum Disposition {
    /** Still worth another attempt (under the attempt cap). */
    REATTEMPTABLE,
    /** Attempt cap hit — recommend RTO. */
    UNDELIVERABLE,
    /** RTO in flight or done. */
    RETURNED,
    /** Case closed successfully. */
    RESOLVED
}
