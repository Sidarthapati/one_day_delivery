package com.oneday.dispatch.batch;

import com.oneday.common.domain.Shift;
import com.oneday.dispatch.service.DaStatusService;
import com.oneday.dispatch.service.DaTaskService;
import com.oneday.dispatch.service.model.DaLiveStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * SC1 — shift-close preparation. ~15 minutes before a shift ends (13:45 for SHIFT_1, 21:45 for SHIFT_2),
 * every DA on the ending shift who is still holding an undelivered parcel gets a {@code RETURN_TO_HUB}
 * carry-back so nothing is stranded when they go offline. The DA brings the parcel back and the hub's
 * dock-receive re-sorts it into a territory bag for the next shift
 * ({@link DaTaskService#spawnShiftCloseReturns}).
 *
 * <p>Mirrors {@link ShiftEndJob}'s "only the ending shift's roster" selection, firing 15 min earlier
 * (the same lead {@code ShiftLoadJob} uses before a shift starts) so the DA has time to bring parcels in
 * before the hard shift-end at 14:05 / 22:05. Idempotent — the carry-back spawn dedupes per parcel.</p>
 */
@Component
public class ShiftClosePrepJob {

    private static final Logger log = LoggerFactory.getLogger(ShiftClosePrepJob.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final DaStatusService daStatusService;
    private final DaTaskService daTaskService;

    public ShiftClosePrepJob(DaStatusService daStatusService, DaTaskService daTaskService) {
        this.daStatusService = daStatusService;
        this.daTaskService = daTaskService;
    }

    @Scheduled(cron = "${dispatch.shift.close-prep-cron:0 45 13,21 * * *}",
            zone = "${dispatch.shift.zone:Asia/Kolkata}")
    public void onSchedule() {
        prepareShiftClose(LocalDate.now(IST), endingShift());
    }

    /** Which shift is ~15 min from ending at the current fire time (before 18:00 = SHIFT_1, else SHIFT_2). */
    static Shift endingShift() {
        return LocalTime.now(IST).getHour() < 18 ? Shift.SHIFT_1 : Shift.SHIFT_2;
    }

    /** Package-visible for direct testing. Fires carry-backs for the ending shift's in-hand deliveries. */
    public void prepareShiftClose(LocalDate date, Shift shift) {
        int spawned = 0;
        int das = 0;
        for (UUID daId : daStatusService.loadedDaIds()) {
            DaLiveStatus live = daStatusService.getLiveStatus(daId);
            if (live == null || !shift.name().equals(live.getShiftType())) {
                continue;   // only the ending shift's roster
            }
            List<UUID> returned = daTaskService.spawnShiftCloseReturns(daId, date);
            if (!returned.isEmpty()) {
                das++;
                spawned += returned.size();
                log.info("Shift-close prep {}: DA {} sent {} in-hand parcel(s) back to hub",
                        shift, daId, returned.size());
            }
        }
        if (spawned > 0) {
            log.info("Shift-close prep {} on {}: {} carry-backs across {} DA(s)", shift, date, spawned, das);
        }
    }
}
