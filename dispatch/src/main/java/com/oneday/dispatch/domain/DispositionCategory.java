package com.oneday.dispatch.domain;

/**
 * What kind of disposition a DA raised. All three hold the DA's territory identically (status
 * {@code ON_BREAK}); they differ only in approval and allowance:
 * <ul>
 *   <li>{@code BREAK} — personal, auto-approved within the slot + 60-min/day allowance, counts allowance.</li>
 *   <li>{@code AUXILIARY} — company work, manager-approved, does not count allowance.</li>
 *   <li>{@code DAY_OFF} — out for the day, manager-approved → existing absence reassignment.</li>
 * </ul>
 */
public enum DispositionCategory {
    BREAK,
    AUXILIARY,
    DAY_OFF
}
