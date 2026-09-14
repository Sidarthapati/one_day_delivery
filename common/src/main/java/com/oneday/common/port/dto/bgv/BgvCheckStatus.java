package com.oneday.common.port.dto.bgv;

/**
 * Lifecycle + verdict of a single BGV check. NOT_INITIATED/IN_PROGRESS are in-flight; GREEN/AMBER/RED/
 * INSUFFICIENT are terminal verdicts (GREEN = clear, AMBER = minor discrepancy, RED = adverse,
 * INSUFFICIENT = not enough info). Mirrors the vendor's Green/Amber/Red dashboard.
 */
public enum BgvCheckStatus {
    NOT_INITIATED,
    IN_PROGRESS,
    GREEN,
    AMBER,
    RED,
    INSUFFICIENT;

    /** A terminal verdict has been reached (no more polling needed). */
    public boolean isTerminal() {
        return this == GREEN || this == AMBER || this == RED || this == INSUFFICIENT;
    }

    /** Counts as a pass for auto-clear purposes (GREEN or a minor AMBER). */
    public boolean isPass() {
        return this == GREEN || this == AMBER;
    }
}
