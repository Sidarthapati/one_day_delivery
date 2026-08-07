package com.oneday.common.domain;

/**
 * The two daily DA shifts. Shared cross-module vocabulary so auth (which assigns a DA to a shift at
 * registration), grid (which plans a territory per shift) and dispatch (which loads/ends a shift's
 * roster) speak the same enum without a compile-time dependency between the modules.
 *
 * <p>Windows align with the dispatch shift cron: SHIFT_1 loads 05:45 / ends 14:05; SHIFT_2 loads
 * 13:45 / ends 22:05 (IST).</p>
 */
public enum Shift {
    SHIFT_1(6, 14),
    SHIFT_2(14, 22);

    private final int startHour;
    private final int endHour;

    Shift(int startHour, int endHour) {
        this.startHour = startHour;
        this.endHour = endHour;
    }

    /** Local (IST) hour the shift begins. */
    public int startHour() {
        return startHour;
    }

    /** Local (IST) hour the shift ends. */
    public int endHour() {
        return endHour;
    }
}
