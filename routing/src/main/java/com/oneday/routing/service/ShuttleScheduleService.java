package com.oneday.routing.service;

import com.oneday.routing.service.model.ShuttleTimetable;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The hub↔airport shuttle (§9). A single origin–destination leg is a <i>timetable</i>, not a VRP:
 * a configurable cadence over the operating window, with arrival ETAs from the OSRM hub↔airport
 * time. Published as {@code SHUTTLE_SCHEDULED} for M9 (its cron-departure input) and M10.
 */
public interface ShuttleScheduleService {

    /** Compute the timetable for {@code cityId}/{@code date} without emitting anything. */
    ShuttleTimetable timetable(UUID cityId, LocalDate date);

    /** Compute the timetable and publish {@code SHUTTLE_SCHEDULED} (called when a plan is published). */
    ShuttleTimetable publish(UUID cityId, LocalDate date, UUID routePlanId);
}
