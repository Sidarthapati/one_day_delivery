package com.oneday.auth.domain;

/** Per-document verification state, set by the applicant (UPLOADED) then the reviewer. */
public enum DocumentStatus {
    UPLOADED,
    VERIFIED,
    REJECTED
}
