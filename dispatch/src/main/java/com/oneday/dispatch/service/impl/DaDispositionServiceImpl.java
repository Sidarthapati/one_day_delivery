package com.oneday.dispatch.service.impl;

import com.oneday.common.domain.Shift;
import com.oneday.common.port.DaDirectoryPort;
import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.domain.DaCronAssignment;
import com.oneday.dispatch.domain.DaDisposition;
import com.oneday.dispatch.domain.DaStatusEnum;
import com.oneday.dispatch.domain.DispositionCategory;
import com.oneday.dispatch.domain.DispositionStatus;
import com.oneday.dispatch.dto.request.DispositionRequest;
import com.oneday.dispatch.dto.response.DispositionResponse;
import com.oneday.dispatch.dto.response.DispositionSlotsResponse;
import com.oneday.dispatch.dto.response.DispositionSlotsResponse.BreakSlot;
import com.oneday.dispatch.dto.response.ManagerDispositionEntry;
import com.oneday.dispatch.repository.DaDispositionRepository;
import com.oneday.dispatch.service.DaDispositionService;
import com.oneday.dispatch.service.DaStatusService;
import com.oneday.dispatch.service.model.DaLiveStatus;
import com.oneday.dispatch.service.model.DaQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Implements {@link DaDispositionService}. Slot windows are the shift day minus each cron / hub-return
 * protected window (uniform across meeting modes — both arrive as a {@link DaCronAssignment}); a BREAK
 * auto-approves only inside a slot and within the 60-min/day allowance and flips the DA {@code ON_BREAK}
 * (territory held, nothing written to the grid). The overstay clock and the reconcile of a break the DA
 * has effectively left (cron freeze, or a manager mark-absent) run in {@link #sweep}.
 */
@Service
class DaDispositionServiceImpl implements DaDispositionService {

    private static final Logger log = LoggerFactory.getLogger(DaDispositionServiceImpl.class);

    /** A disposition that is "live" (holds a DA slot) — blocks a second concurrent request. */
    private static final List<DispositionStatus> LIVE =
            List.of(DispositionStatus.PENDING, DispositionStatus.ACTIVE, DispositionStatus.OVERSTAYED);
    /** Statuses in effect (DA is ON_BREAK) that {@code end} may close. */
    private static final List<DispositionStatus> IN_EFFECT =
            List.of(DispositionStatus.ACTIVE, DispositionStatus.OVERSTAYED);
    /** A DA may raise a disposition only while genuinely available. */
    private static final Set<DaStatusEnum> REQUESTABLE_FROM =
            Set.of(DaStatusEnum.IDLE, DaStatusEnum.IN_PROGRESS);

    private final DaDispositionRepository repository;
    private final DaStatusService daStatusService;
    private final DaDirectoryPort daDirectory;
    private final DispatchProperties props;
    /** System clock in prod; a fixed clock in tests (via {@link #setClock}). */
    private Clock clock = Clock.systemUTC();

    DaDispositionServiceImpl(DaDispositionRepository repository, DaStatusService daStatusService,
                             DaDirectoryPort daDirectory, DispatchProperties props) {
        this.repository = repository;
        this.daStatusService = daStatusService;
        this.daDirectory = daDirectory;
        this.props = props;
    }

    /** Package-visible for deterministic tests. */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    // ── DA-facing ──────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public DispositionSlotsResponse slots(UUID daId) {
        DispatchProperties.Disposition cfg = props.getDisposition();
        DaLiveStatus live = daStatusService.getLiveStatus(daId);
        DaDisposition activeRow =
                repository.findFirstByDaIdAndOperatingDateAndStatusIn(daId, today(), LIVE).orElse(null);
        DispositionResponse active = activeRow != null ? DispositionResponse.of(activeRow) : null;
        int remaining = remainingAllowance(daId, cfg);
        if (live == null || live.getShiftType() == null) {
            // Not loaded / no shift yet — render an empty card rather than error.
            return new DispositionSlotsResponse(List.of(), cfg.getDailyAllowanceMinutes(), remaining,
                    cfg.getMinBreakMinutes(), active);
        }
        List<BreakSlot> slots = computeSlots(daId, live, clock.instant());
        return new DispositionSlotsResponse(slots, cfg.getDailyAllowanceMinutes(), remaining,
                cfg.getMinBreakMinutes(), active);
    }

    @Override
    @Transactional
    public DispositionResponse request(UUID daId, DispositionRequest req, UUID actorUserId) {
        try {
            return doRequest(daId, req, actorUserId);
        } catch (DataIntegrityViolationException race) {
            // Lost the check-then-insert race against uq_da_disposition_live_per_da — surface the same
            // 409 the pre-check would have (Spring would otherwise map this to a 500).
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You already have an active or pending request");
        }
    }

    private DispositionResponse doRequest(UUID daId, DispositionRequest req, UUID actorUserId) {
        if (req == null || req.category() == null || req.reason() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "category and reason are required");
        }
        if (repository.findFirstByDaIdAndOperatingDateAndStatusIn(daId, today(), LIVE).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You already have an active or pending request");
        }
        DaLiveStatus live = daStatusService.getLiveStatus(daId);
        if (live == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You are not on shift");
        }
        DaStatusEnum status = daStatusService.getStatus(daId);
        if (!REQUESTABLE_FROM.contains(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot request a break while " + status);
        }

        DispatchProperties.Disposition cfg = props.getDisposition();
        DaDisposition d = new DaDisposition();
        d.setDaId(daId);
        d.setCityId(live.getCityId());
        d.setOperatingDate(today());
        d.setCategory(req.category());
        d.setReason(req.reason());
        d.setNote(req.note());
        d.setCreatedBy(actorUserId);
        d.setCountsAllowance(req.category() == DispositionCategory.BREAK
                && !cfg.getNonCountingReasons().contains(req.reason().name()));

        if (req.category() == DispositionCategory.BREAK) {
            activateBreak(d, req, live, cfg);
        } else {
            // AUXILIARY / DAY_OFF — manager decides; the DA keeps working until approved. DAY_OFF ignores
            // any minutes; an AUXILIARY estimate is optional (open-ended) but, if given, must be positive
            // (a zero/negative estimate would put scheduledEnd in the past → instantly OVERSTAYED).
            Integer minutes = req.category() == DispositionCategory.DAY_OFF ? null : req.minutes();
            if (minutes != null && minutes <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "minutes must be positive");
            }
            d.setStatus(DispositionStatus.PENDING);
            d.setDurationMinutes(minutes);
            repository.saveAndFlush(d);
            log.info("DA {} raised {} ({}) — PENDING manager approval", daId, req.category(), req.reason());
        }
        return DispositionResponse.of(d);
    }

    /** Validate a personal break against the allowance + an allowed slot, then activate it (→ ON_BREAK). */
    private void activateBreak(DaDisposition d, DispositionRequest req, DaLiveStatus live,
                               DispatchProperties.Disposition cfg) {
        Integer minutes = req.minutes();
        if (minutes == null || minutes <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "minutes is required for a break");
        }
        int remaining = remainingAllowance(d.getDaId(), cfg);
        if (minutes > remaining) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Break exceeds your daily allowance (" + remaining + " min left)");
        }
        Instant now = clock.instant();
        Instant end = now.plus(Duration.ofMinutes(minutes));
        boolean fits = computeSlots(d.getDaId(), live, now).stream()
                .anyMatch(s -> !now.isBefore(s.start()) && !end.isAfter(s.end()));
        if (!fits) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That break does not fit an allowed slot (it would risk your cron / hub-return)");
        }
        d.setStatus(DispositionStatus.ACTIVE);
        d.setDurationMinutes(minutes);
        d.setScheduledStart(now);
        d.setActualStart(now);
        d.setScheduledEnd(end);
        repository.saveAndFlush(d);
        daStatusService.withDaLock(d.getDaId(), () -> {
            // Re-check under the lock: if a cron freeze flipped the DA to CRON_LOCKED between the guard in
            // doRequest and here, cron wins — don't clobber it (mirrors approve / restoreToWork).
            DaStatusEnum current = daStatusService.getStatus(d.getDaId());
            if (!REQUESTABLE_FROM.contains(current)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Cannot start a break while " + current);
            }
            daStatusService.updateStatus(d.getDaId(), DaStatusEnum.ON_BREAK);
            return null;
        });
        log.info("DA {} on BREAK ({}) for {} min → {}", d.getDaId(), d.getReason(), minutes, end);
    }

    @Override
    @Transactional
    public DispositionResponse end(UUID daId) {
        DaDisposition d = repository.findFirstByDaIdAndOperatingDateAndStatusIn(daId, today(), IN_EFFECT)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "No active break to end"));
        Instant now = clock.instant();
        d.setActualEnd(now);
        // Refine the charged duration to actual elapsed (capped at requested) so an early return refunds.
        if (d.getActualStart() != null && d.getDurationMinutes() != null) {
            long elapsed = (long) Math.ceil(Duration.between(d.getActualStart(), now).getSeconds() / 60.0);
            d.setDurationMinutes((int) Math.max(1, Math.min(d.getDurationMinutes(), elapsed)));
        }
        d.setStatus(DispositionStatus.COMPLETED);
        repository.save(d);
        restoreToWork(daId);
        log.info("DA {} ended {} ({})", daId, d.getCategory(), d.getReason());
        return DispositionResponse.of(d);
    }

    // ── Manager-facing ─────────────────────────────────────────────────────────

    @Override
    @Transactional
    public DispositionResponse approve(UUID dispositionId, UUID managerId, UUID scopeCityId) {
        DaDisposition d = load(dispositionId, scopeCityId);
        if (d.getCategory() != DispositionCategory.AUXILIARY) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only auxiliary requests are approved here; a day-off is actioned via Mark absent");
        }
        if (d.getStatus() == DispositionStatus.ACTIVE) {
            return DispositionResponse.of(d);   // idempotent
        }
        if (d.getStatus() != DispositionStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Request is " + d.getStatus());
        }
        Instant now = clock.instant();
        d.setStatus(DispositionStatus.ACTIVE);
        d.setApprovedBy(managerId);
        d.setActualStart(now);
        d.setScheduledStart(now);
        if (d.getDurationMinutes() != null) {
            d.setScheduledEnd(now.plus(Duration.ofMinutes(d.getDurationMinutes())));
        }
        repository.save(d);
        daStatusService.withDaLock(d.getDaId(), () -> {
            if (daStatusService.getLiveStatus(d.getDaId()) != null) {
                daStatusService.updateStatus(d.getDaId(), DaStatusEnum.ON_BREAK);
            }
            return null;
        });
        log.info("Manager {} approved auxiliary {} for DA {}", managerId, dispositionId, d.getDaId());
        return DispositionResponse.of(d);
    }

    @Override
    @Transactional
    public DispositionResponse reject(UUID dispositionId, UUID managerId, UUID scopeCityId) {
        DaDisposition d = load(dispositionId, scopeCityId);
        if (d.getStatus() != DispositionStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Request is " + d.getStatus());
        }
        d.setStatus(DispositionStatus.REJECTED);
        d.setApprovedBy(managerId);
        repository.save(d);
        log.info("Manager {} rejected {} for DA {}", managerId, dispositionId, d.getDaId());
        return DispositionResponse.of(d);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ManagerDispositionEntry> managerView(UUID cityId) {
        List<DaDisposition> rows = repository.findByCityIdAndOperatingDateAndStatusIn(
                cityId, today(), LIVE);
        List<UUID> daIds = rows.stream().map(DaDisposition::getDaId).distinct().toList();
        Map<UUID, DaDirectoryPort.DaContact> names = daDirectory.contactsFor(daIds);
        Instant now = clock.instant();
        return rows.stream()
                .map(d -> {
                    DaDirectoryPort.DaContact c = names.get(d.getDaId());
                    long overdue = d.getScheduledEnd() != null && now.isAfter(d.getScheduledEnd())
                            ? Duration.between(d.getScheduledEnd(), now).toMinutes() : 0;
                    return new ManagerDispositionEntry(d.getId(), d.getDaId(),
                            c != null ? c.name() : null, d.getCategory(), d.getReason(), d.getStatus(),
                            d.getNote(), d.getScheduledEnd(), d.getEscalationLevel(), overdue);
                })
                // Most urgent first: OVERSTAYED, then PENDING, then ACTIVE; then most overdue.
                .sorted(Comparator.comparingInt((ManagerDispositionEntry e) -> statusRank(e.status()))
                        .thenComparing(Comparator.comparingLong(ManagerDispositionEntry::minutesOverdue).reversed()))
                .toList();
    }

    // ── Monitor sweep ──────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void sweep(Instant now) {
        DispatchProperties.Disposition cfg = props.getDisposition();
        LocalDate today = today();
        // Close any live row carried over from a prior day (an OVERSTAYED break never reconciled, or an
        // un-actioned PENDING day-off) so it can never permanently block the DA's future requests.
        for (DaDisposition stale : repository.findByOperatingDateBeforeAndStatusIn(today, LIVE)) {
            log.warn("Auto-cancelling carry-over {} disposition {} for DA {} from {}",
                    stale.getStatus(), stale.getId(), stale.getDaId(), stale.getOperatingDate());
            stale.setStatus(DispositionStatus.CANCELLED);
            if (stale.getActualEnd() == null) {
                stale.setActualEnd(now);
            }
            repository.save(stale);
            restoreToWork(stale.getDaId());
        }
        for (DaDisposition d : repository.findByOperatingDateAndStatus(today, DispositionStatus.ACTIVE)) {
            // Reconcile: if the DA is no longer ON_BREAK (cron freeze took over, or a manager marked them
            // absent), the break is effectively over — close it so the allowance/records stay honest.
            DaStatusEnum status = daStatusService.getStatus(d.getDaId());
            if (status != null && status != DaStatusEnum.ON_BREAK) {
                d.setStatus(DispositionStatus.COMPLETED);
                d.setActualEnd(now);
                repository.save(d);
                continue;
            }
            if (d.getScheduledEnd() == null) {
                continue;   // open-ended (auxiliary with no estimate) — manager tracks it
            }
            long past = Duration.between(d.getScheduledEnd(), now).toMinutes();
            if (past <= 0) {
                continue;
            }
            int level = Math.min(cfg.getMaxEscalationLevel(),
                    1 + (int) (past / Math.max(1, cfg.getEscalationStepMinutes())));
            boolean changed = false;
            if (level != d.getEscalationLevel()) {
                d.setEscalationLevel(level);
                changed = true;
            }
            if (past >= cfg.getEscalateAfterMinutes()) {
                d.setStatus(DispositionStatus.OVERSTAYED);   // surfaced to the manager; never auto-vacate
                changed = true;
                log.warn("DA {} OVERSTAYED {} by {} min — escalated to station manager",
                        d.getDaId(), d.getCategory(), past);
            }
            if (changed) {
                repository.save(d);
            }
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Restore a returning DA to work — only if we still hold them ON_BREAK (don't clobber CRON_LOCKED). */
    private void restoreToWork(UUID daId) {
        daStatusService.withDaLock(daId, () -> {
            if (daStatusService.getStatus(daId) == DaStatusEnum.ON_BREAK) {
                daStatusService.updateStatus(daId, DaStatusEnum.IDLE);
            }
            return null;
        });
    }

    private int remainingAllowance(UUID daId, DispatchProperties.Disposition cfg) {
        int used = repository.findByDaIdAndOperatingDate(daId, today()).stream()
                .filter(DaDisposition::isCountsAllowance)
                .filter(d -> d.getStatus() == DispositionStatus.ACTIVE
                        || d.getStatus() == DispositionStatus.OVERSTAYED
                        || d.getStatus() == DispositionStatus.COMPLETED)
                .mapToInt(d -> d.getDurationMinutes() != null ? d.getDurationMinutes() : 0)
                .sum();
        return Math.max(0, cfg.getDailyAllowanceMinutes() - used);
    }

    private DaDisposition load(UUID id, UUID scopeCityId) {
        DaDisposition d = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No disposition " + id));
        if (scopeCityId != null && !scopeCityId.equals(d.getCityId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No disposition " + id);
        }
        return d;
    }

    private static int statusRank(DispositionStatus s) {
        return switch (s) {
            case OVERSTAYED -> 0;
            case PENDING -> 1;
            case ACTIVE -> 2;
            default -> 3;
        };
    }

    /**
     * The allowed break windows today = the shift day minus each cron / hub-return protected window
     * {@code [meeting − freezeMinutes, meeting]}, keeping only gaps at least {@code minBreakMinutes} wide.
     * Windows start no earlier than {@code from} (so past time isn't offered) and end at shift end.
     */
    private List<BreakSlot> computeSlots(UUID daId, DaLiveStatus live, Instant from) {
        ZoneId zone = ZoneId.of(props.getShift().getZone());
        Shift shift;
        try {
            shift = Shift.valueOf(live.getShiftType());
        } catch (IllegalArgumentException | NullPointerException e) {
            return List.of();
        }
        LocalDate today = today();
        Instant shiftStart = today.atTime(shift.startHour(), 0).atZone(zone).toInstant();
        Instant shiftEnd = today.atTime(shift.endHour(), 0).atZone(zone).toInstant();
        Instant dayStart = from.isAfter(shiftStart) ? from : shiftStart;
        int minBreak = props.getDisposition().getMinBreakMinutes();
        if (!dayStart.isBefore(shiftEnd)) {
            return List.of();
        }

        List<Instant[]> protectedIv = protectedWindows(daId, today, zone, shiftStart, shiftEnd);
        List<BreakSlot> slots = new ArrayList<>();
        Instant cursor = dayStart;
        for (Instant[] p : protectedIv) {
            Instant pStart = p[0];
            Instant pEnd = p[1];
            if (pStart.isAfter(cursor)) {
                Instant winEnd = pStart.isBefore(shiftEnd) ? pStart : shiftEnd;
                addIfWideEnough(slots, cursor, winEnd, minBreak);
            }
            if (pEnd.isAfter(cursor)) {
                cursor = pEnd;
            }
            if (!cursor.isBefore(shiftEnd)) {
                break;
            }
        }
        if (cursor.isBefore(shiftEnd)) {
            addIfWideEnough(slots, cursor, shiftEnd, minBreak);
        }
        return slots;
    }

    /** Merged, sorted, clamped protected windows around each of the DA's cron / hub-return meetings. */
    private List<Instant[]> protectedWindows(UUID daId, LocalDate today, ZoneId zone,
                                             Instant shiftStart, Instant shiftEnd) {
        DaQueue q = daStatusService.getQueue(daId);
        DaCronAssignment cron = q != null ? q.getCron() : null;
        if (cron == null) {
            return List.of();
        }
        long freeze = props.getCron().getFreezeMinutes();
        List<Instant> meetings = new ArrayList<>();
        if (cron.getMeetingTimes() != null && !cron.getMeetingTimes().isEmpty()) {
            for (String t : cron.getMeetingTimes()) {
                try {
                    meetings.add(today.atTime(LocalTime.parse(t)).atZone(zone).toInstant());
                } catch (RuntimeException ignore) {
                    // skip an unparseable entry
                }
            }
        } else if (cron.getScheduledMeetingTime() != null) {
            meetings.add(cron.getScheduledMeetingTime());
        }
        List<Instant[]> raw = new ArrayList<>();
        for (Instant m : meetings) {
            Instant s = m.minus(Duration.ofMinutes(freeze));
            Instant clampedStart = s.isBefore(shiftStart) ? shiftStart : s;
            Instant clampedEnd = m.isAfter(shiftEnd) ? shiftEnd : m;
            if (clampedEnd.isAfter(clampedStart)) {
                raw.add(new Instant[] {clampedStart, clampedEnd});
            }
        }
        raw.sort(Comparator.comparing(a -> a[0]));
        // Merge overlaps.
        List<Instant[]> merged = new ArrayList<>();
        for (Instant[] iv : raw) {
            if (!merged.isEmpty() && !iv[0].isAfter(merged.get(merged.size() - 1)[1])) {
                Instant[] last = merged.get(merged.size() - 1);
                if (iv[1].isAfter(last[1])) {
                    last[1] = iv[1];
                }
            } else {
                merged.add(iv);
            }
        }
        return merged;
    }

    private void addIfWideEnough(List<BreakSlot> slots, Instant start, Instant end, int minMinutes) {
        if (Duration.between(start, end).toMinutes() >= minMinutes) {
            slots.add(new BreakSlot(start, end));
        }
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneId.of(props.getShift().getZone()));
    }
}
