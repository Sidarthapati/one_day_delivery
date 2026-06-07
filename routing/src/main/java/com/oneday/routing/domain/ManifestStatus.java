package com.oneday.routing.domain;

/** Van manifest lifecycle (§11.2, M6-D-015). */
public enum ManifestStatus {
    BUILDING,
    LOADED,
    IN_PROGRESS,
    RETURNED,
    RECONCILED
}
