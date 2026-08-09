package com.oneday.dispatch.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.common.domain.Shift;
import com.oneday.dispatch.batch.ShiftLoadJob;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Ops trigger to (re)load a shift's DA roster into the in-memory assignment engine without waiting
 * for the scheduled 05:45/13:45 load — e.g. after registering a DA + approving a plan mid-shift.
 * ADMIN / STATION_MANAGER only. Defaults to today (IST) and the current shift.
 */
@RestController
public class ShiftAdminController {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final ShiftLoadJob shiftLoadJob;

    public ShiftAdminController(ShiftLoadJob shiftLoadJob) {
        this.shiftLoadJob = shiftLoadJob;
    }

    @PostMapping("/dispatch/admin/shift-load")
    public ResponseEntity<Void> loadShift(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Shift shift,
            @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal, Authz.STATION_MANAGER);   // ADMIN always allowed
        LocalDate d = date != null ? date : LocalDate.now(IST);
        Shift s = shift != null ? shift
                : (LocalTime.now(IST).getHour() < 12 ? Shift.SHIFT_1 : Shift.SHIFT_2);
        shiftLoadJob.loadShiftsForDate(d, s);
        return ResponseEntity.noContent().build();
    }
}
