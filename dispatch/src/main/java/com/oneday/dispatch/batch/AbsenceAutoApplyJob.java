package com.oneday.dispatch.batch;

import com.oneday.dispatch.domain.AbsenceStatus;
import com.oneday.dispatch.domain.DaAbsenceEvent;
import com.oneday.dispatch.repository.DaAbsenceEventRepository;
import com.oneday.dispatch.service.AbsenceReassignmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Auto-approves a previewed absence plan the station manager didn't act on: any PENDING
 * {@code da_absence_event} past its {@code auto_approve_at} deadline is applied automatically
 * (AUTO_APPLIED). Honours "manager approves intraday changes" while an absent DA is being covered —
 * respected when someone's watching, self-healing when they're not.
 */
@Component
public class AbsenceAutoApplyJob {

    private static final Logger log = LoggerFactory.getLogger(AbsenceAutoApplyJob.class);

    private final DaAbsenceEventRepository absenceRepository;
    private final AbsenceReassignmentService absenceService;

    public AbsenceAutoApplyJob(DaAbsenceEventRepository absenceRepository,
                               AbsenceReassignmentService absenceService) {
        this.absenceRepository = absenceRepository;
        this.absenceService = absenceService;
    }

    @Scheduled(fixedDelayString = "${dispatch.absence.auto-apply-sweep-ms:60000}")
    public void run() {
        sweep(Instant.now());
    }

    /** Package-visible for direct testing with a fixed clock. */
    void sweep(Instant now) {
        for (DaAbsenceEvent event : absenceRepository.findByStatusAndAutoApproveAtBefore(AbsenceStatus.PENDING, now)) {
            try {
                absenceService.autoApply(event.getId());
                log.info("Auto-applied absence plan {} (deadline lapsed)", event.getId());
            } catch (RuntimeException e) {
                log.error("Auto-apply of absence plan {} failed: {}", event.getId(), e.getMessage(), e);
            }
        }
    }
}
