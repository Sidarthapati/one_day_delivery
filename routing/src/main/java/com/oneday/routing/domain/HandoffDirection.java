package com.oneday.routing.domain;

/** Direction of a manifest item at a meeting stop (§11.1). */
public enum HandoffDirection {
    DELIVER,   // van → DA (last-mile)
    COLLECT    // DA → van (first-mile)
}
