package com.oneday.orders.service.impl;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The current calendar-month window in IST — the period a per-member spend budget resets on. Month
 * boundaries follow Indian wall-clock time (the platform's convention, {@code Asia/Kolkata}), so a
 * budget rolls over at IST midnight on the 1st, not UTC.
 */
final class MonthWindow {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private MonthWindow() {}

    /** The instant of 00:00 IST on the first day of the current month. */
    static Instant startOfCurrentMonth() {
        return startOfMonthFor(Instant.now());
    }

    /** The instant of 00:00 IST on the first day of the IST month containing {@code at}. */
    static Instant startOfMonthFor(Instant at) {
        LocalDate firstOfMonth = at.atZone(IST).toLocalDate().withDayOfMonth(1);
        return firstOfMonth.atStartOfDay(IST).toInstant();
    }
}
