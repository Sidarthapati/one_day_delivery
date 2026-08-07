package com.oneday.common.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * The scheduled-pickup slot grid: two-hour windows across operating hours (07:00–21:00 IST →
 * 07-09, 09-11, … 19-21). Shared so booking (M4), dispatch hold/release (M5) and any UI speak one
 * grid. Windows are IST wall-clock resolved to absolute instants for a given date.
 */
public final class PickupSlots {

    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    public static final int OPERATING_START_HOUR = 7;   // first slot starts here
    public static final int OPERATING_END_HOUR = 21;    // last slot ends here
    public static final int SLOT_HOURS = 2;

    private PickupSlots() {}

    /** Valid slot start hours: 7, 9, 11, 13, 15, 17, 19. */
    public static List<Integer> startHours() {
        return java.util.stream.IntStream
                .iterate(OPERATING_START_HOUR, h -> h + SLOT_HOURS < OPERATING_END_HOUR + 1, h -> h + SLOT_HOURS)
                .boxed()
                .filter(h -> h + SLOT_HOURS <= OPERATING_END_HOUR)
                .toList();
    }

    public static boolean isValidStartHour(int startHour) {
        return startHours().contains(startHour);
    }

    /** A resolved [start, end) window as absolute instants. */
    public record Window(Instant start, Instant end) {}

    /** Resolve a slot (date + IST start hour) to absolute instants. */
    public static Window resolve(LocalDate date, int startHour) {
        ZonedDateTime start = date.atTime(startHour, 0).atZone(ZONE);
        return new Window(start.toInstant(), start.plusHours(SLOT_HOURS).toInstant());
    }

    /**
     * The next operating-window start at/after {@code now} (for ASAP orders placed off-hours). Within
     * operating hours → {@code now} (assign immediately). Before 07:00 → today 07:00. At/after 21:00 →
     * tomorrow 07:00.
     */
    public static Instant nextOperatingStart(Instant now) {
        ZonedDateTime z = now.atZone(ZONE);
        int hour = z.getHour();
        if (hour < OPERATING_START_HOUR) {
            return z.toLocalDate().atTime(OPERATING_START_HOUR, 0).atZone(ZONE).toInstant();
        }
        if (hour >= OPERATING_END_HOUR) {
            return z.toLocalDate().plusDays(1).atTime(OPERATING_START_HOUR, 0).atZone(ZONE).toInstant();
        }
        return now;
    }
}
