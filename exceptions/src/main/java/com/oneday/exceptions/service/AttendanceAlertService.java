package com.oneday.exceptions.service;

import com.oneday.common.kafka.events.DaLifecycleEvent;
import com.oneday.exceptions.dto.AttendanceAlertResponse;

import java.time.LocalDate;
import java.util.List;

/**
 * M11 capture of geocoded-attendance alerts raised by M5. An {@code ATTENDANCE_UNCONFIRMED} event
 * opens an alert; {@code ATTENDANCE_RESOLVED} closes it. The station console reads the open queue.
 */
public interface AttendanceAlertService {

    /** Open (or dedupe) an alert from an {@code ATTENDANCE_UNCONFIRMED} event. */
    void raise(DaLifecycleEvent event);

    /** Close the matching alert from an {@code ATTENDANCE_RESOLVED} event. */
    void resolve(DaLifecycleEvent event);

    /** Open alerts for a date, optionally scoped to one city (code or UUID string); null scope = all cities. */
    List<AttendanceAlertResponse> openAlerts(String cityScope, LocalDate date);
}
