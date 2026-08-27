package com.oneday.dispatch.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.common.domain.Shift;
import com.oneday.dispatch.dto.request.AttendanceCheckInRequest;
import com.oneday.dispatch.dto.response.AbsencePreviewResponse;
import com.oneday.dispatch.dto.response.AttendanceMusterEntry;
import com.oneday.dispatch.service.AttendanceService;
import com.oneday.grid.service.GridService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Geocoded DA attendance. The DA self-checks in ("I've arrived"); a station manager reads the muster
 * and settles unconfirmed DAs — discard→present, or mark→absent (which returns the reassignment
 * preview, same as the DA-absence console). STATION_MANAGER is pinned to their own city; ADMIN any.
 */
@RestController
public class AttendanceController {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final AttendanceService attendanceService;
    private final GridService gridService;

    public AttendanceController(AttendanceService attendanceService, GridService gridService) {
        this.attendanceService = attendanceService;
        this.gridService = gridService;
    }

    /** DA self "I've arrived": marks present if within the hub geofence, else 422. */
    @PostMapping("/dispatch/da/{daId}/attendance/check-in")
    public AttendanceMusterEntry checkIn(@PathVariable UUID daId,
                                         @AuthenticationPrincipal AuthUserDetails principal,
                                         @RequestBody(required = false) AttendanceCheckInRequest request) {
        Authz.requireDaSelf(principal, daId);
        Double lat = request != null ? request.lat() : null;
        Double lon = request != null ? request.lon() : null;
        return attendanceService.checkIn(daId, lat, lon);
    }

    /** The DA's own attendance for today (PRESENT/ABSENT/PENDING) — lets the driver app reflect a GPS
     *  auto-present, not just a tap in the current session. */
    @GetMapping("/dispatch/da/{daId}/attendance/today")
    public AttendanceMusterEntry today(@PathVariable UUID daId,
                                       @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireDaSelf(principal, daId);
        return attendanceService.today(daId);
    }

    /** The day's muster for a city + shift: rostered DAs with their present/absent/pending state. */
    @GetMapping("/dispatch/attendance/muster")
    public List<AttendanceMusterEntry> muster(@RequestParam(required = false) UUID cityId,
                                              @RequestParam Shift shift,
                                              @RequestParam(required = false)
                                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                              @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, Authz.STATION_MANAGER);
        String cityCode = resolveCityCode(principal, cityId);
        LocalDate day = date != null ? date : LocalDate.now(IST);
        return attendanceService.muster(cityCode, day, shift);
    }

    /** Station manager confirms a DA present (discards the attendance alert). */
    @PostMapping("/dispatch/attendance/{daId}/present")
    public ResponseEntity<Void> markPresent(@PathVariable UUID daId,
                                            @RequestParam(required = false)
                                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                            @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, Authz.STATION_MANAGER);
        UUID actor = Authz.requireUserId(principal);
        UUID scope = Authz.isAdmin(principal) ? null : managerCity(principal);
        LocalDate day = date != null ? date : LocalDate.now(IST);
        attendanceService.markPresent(daId, day, actor, scope);
        return ResponseEntity.noContent().build();
    }

    /** Station manager marks a DA absent → records ABSENT and returns the reassignment preview to apply. */
    @PostMapping("/dispatch/attendance/{daId}/absent")
    public AbsencePreviewResponse markAbsent(@PathVariable UUID daId,
                                             @RequestParam(required = false)
                                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                             @RequestParam(required = false) String reason,
                                             @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, Authz.STATION_MANAGER);
        UUID actor = Authz.requireUserId(principal);
        UUID scope = Authz.isAdmin(principal) ? null : managerCity(principal);
        LocalDate day = date != null ? date : LocalDate.now(IST);
        String why = reason != null && !reason.isBlank() ? reason : "Attendance no-show";
        return attendanceService.markAbsent(daId, day, why, actor, scope);
    }

    // ── city scoping (mirrors AbsenceController) ───────────────────────────────

    /** The grid city code for the muster: STATION_MANAGER → their own city; ADMIN → the {@code cityId} param. */
    private String resolveCityCode(AuthUserDetails principal, UUID cityIdParam) {
        UUID cityId;
        if (Authz.isAdmin(principal)) {
            if (cityIdParam == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ADMIN must specify cityId");
            }
            cityId = cityIdParam;
        } else {
            cityId = managerCity(principal);
            if (cityIdParam != null && !cityIdParam.equals(cityId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot act outside your city");
            }
        }
        String code = gridService.resolveCityCode(cityId);
        if (code == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown city " + cityId);
        }
        return code;
    }

    private UUID managerCity(AuthUserDetails principal) {
        String city = principal.getUser().getCityId();
        if (city == null || city.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Station manager has no city scope");
        }
        try {
            return UUID.fromString(city);
        } catch (IllegalArgumentException notUuid) {
            return gridService.resolveCityId(city);
        }
    }
}
