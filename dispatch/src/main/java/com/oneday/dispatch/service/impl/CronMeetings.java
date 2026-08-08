package com.oneday.dispatch.service.impl;

import com.oneday.dispatch.domain.DaCronAssignment;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;

/** Shared cron-meeting helpers used by both assignment (insertion) and reorder. */
final class CronMeetings {

    private CronMeetings() {}

    /**
     * The DA's next reachable van meeting: the earliest of the cron's {@code meetingTimes} strictly
     * after {@code now}. M6 emits the whole day's loop meetings but v1 pins
     * {@code scheduled_meeting_time} to the morning slot and never rolls it forward — so measuring
     * slack against a meeting that already passed would mark every task infeasible mid-day. Computing
     * the next future meeting keeps the gate honest at any hour; falls back to the persisted primary
     * when the list is empty or the day is genuinely over (→ correctly infeasible).
     */
    static Instant activeMeetingTime(DaCronAssignment cron, Instant now, ZoneId zone) {
        return cron.getMeetingTimes().stream()
                .map(LocalTime::parse)
                .map(t -> LocalDateTime.of(cron.getOperatingDate(), t).atZone(zone).toInstant())
                .filter(i -> i.isAfter(now))
                .min(Comparator.naturalOrder())
                .orElse(cron.getScheduledMeetingTime());
    }
}
