package com.oneday.exceptions.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.exceptions.dto.AttendanceAlertResponse;
import com.oneday.exceptions.service.AttendanceAlertService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * The station console's attendance-alert inbox. Read-only here — the actions (mark present / mark
 * absent) live in M5 dispatch, which emits {@code ATTENDANCE_RESOLVED} to close the alert. A
 * STATION_MANAGER sees their own city's open alerts; ADMIN sees all.
 */
@RestController
@RequestMapping("/api/v1/attendance/alerts")
public class AttendanceAlertController {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final AttendanceAlertService service;

    public AttendanceAlertController(AttendanceAlertService service) {
        this.service = service;
    }

    /** Open attendance alerts for a date (default today), scoped to the caller's city (ADMIN → all). */
    @GetMapping
    public List<AttendanceAlertResponse> open(
            @AuthenticationPrincipal AuthUserDetails principal,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        Authz.requireRole(principal, Authz.STATION_MANAGER, Authz.SUPERVISOR, Authz.CALL_CENTER_AGENT);
        String scope = Authz.cityScope(principal);
        LocalDate day = date != null ? date : LocalDate.now(IST);
        return service.openAlerts(scope, day);
    }
}
