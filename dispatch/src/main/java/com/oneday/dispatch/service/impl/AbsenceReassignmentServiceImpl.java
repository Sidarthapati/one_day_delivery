package com.oneday.dispatch.service.impl;

import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.common.domain.Shift;
import com.oneday.common.port.DaDirectoryPort;
import com.oneday.dispatch.domain.AbsenceStatus;
import com.oneday.dispatch.domain.DaStatus;
import com.oneday.dispatch.domain.DaAbsenceEvent;
import com.oneday.dispatch.domain.DaStatusEnum;
import com.oneday.dispatch.domain.DispatchQueue;
import com.oneday.dispatch.dto.response.DaRosterEntry;
import com.oneday.dispatch.repository.DaStatusRepository;
import com.oneday.dispatch.domain.TaskStatus;
import com.oneday.dispatch.domain.TaskType;
import com.oneday.dispatch.dto.response.AbsenceApplyResponse;
import com.oneday.dispatch.dto.response.AbsencePreviewResponse;
import com.oneday.dispatch.dto.response.AbsencePreviewResponse.CustodyMove;
import com.oneday.dispatch.dto.response.AbsencePreviewResponse.ReceiverLoad;
import com.oneday.dispatch.dto.response.AbsencePreviewResponse.TaskMove;
import com.oneday.dispatch.repository.DaAbsenceEventRepository;
import com.oneday.dispatch.repository.DispatchQueueRepository;
import com.oneday.dispatch.service.AbsenceReassignmentService;
import com.oneday.dispatch.service.DaStatusService;
import com.oneday.dispatch.service.model.DaLiveStatus;
import com.oneday.grid.dto.response.AbsenceReassignmentPlan;
import com.oneday.grid.dto.response.AbsenceReassignmentPlan.HexReassignment;
import com.oneday.grid.dto.response.AssignmentResponse;
import com.oneday.grid.service.GridService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Orchestrates midday DA absence. Preview computes the M3 territory split + the per-task consequence
 * (loose task → new owner, in-custody parcel → CUSTODY_COLLECT handoff, orphan) without mutating
 * anything. Apply commits the grid override, then makes each task follow its hex: a not-yet-collected
 * task is re-created on the new owner and the reorder parks it before/beyond the cron; an in-custody
 * parcel becomes a CUSTODY_COLLECT task (collect from the absent DA's last location). Never routes
 * through the cron-feasibility gate — the task goes to the hex owner regardless of the cron.
 */
@Service
class AbsenceReassignmentServiceImpl implements AbsenceReassignmentService {

    private static final Logger log = LoggerFactory.getLogger(AbsenceReassignmentServiceImpl.class);
    private static final List<TaskStatus> ACTIVE = List.of(TaskStatus.QUEUED, TaskStatus.IN_PROGRESS);

    // On-shift DAs (any of these) are candidates for the absence picker; OFFLINE / ABSENT are excluded.
    private static final List<DaStatusEnum> ON_SHIFT = List.of(
            DaStatusEnum.IDLE, DaStatusEnum.IN_PROGRESS, DaStatusEnum.CRON_LOCKED, DaStatusEnum.AT_CRON);

    private final GridService gridService;
    private final DispatchQueueRepository queueRepository;
    private final DaAbsenceEventRepository absenceRepository;
    private final DaStatusService daStatusService;
    private final DaStatusRepository daStatusRepository;
    private final DaDirectoryPort daDirectory;
    private final QueueReorderService reorderService;
    private final DispatchProperties props;

    AbsenceReassignmentServiceImpl(GridService gridService,
                                   DispatchQueueRepository queueRepository,
                                   DaAbsenceEventRepository absenceRepository,
                                   DaStatusService daStatusService,
                                   DaStatusRepository daStatusRepository,
                                   DaDirectoryPort daDirectory,
                                   QueueReorderService reorderService,
                                   DispatchProperties props) {
        this.gridService = gridService;
        this.queueRepository = queueRepository;
        this.absenceRepository = absenceRepository;
        this.daStatusService = daStatusService;
        this.daStatusRepository = daStatusRepository;
        this.daDirectory = daDirectory;
        this.reorderService = reorderService;
        this.props = props;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DaRosterEntry> roster(UUID scopeCityId, LocalDate date, Shift shift) {
        String shiftType = shift != null ? shift.name() : null;
        List<com.oneday.dispatch.domain.DaStatus> onShift;
        if (shiftType != null) {
            onShift = scopeCityId == null
                    ? daStatusRepository.findByShiftDateAndShiftTypeAndStatusIn(date, shiftType, ON_SHIFT)
                    : daStatusRepository.findByCityIdAndShiftDateAndShiftTypeAndStatusIn(scopeCityId, date, shiftType, ON_SHIFT);
        } else {
            onShift = scopeCityId == null
                    ? daStatusRepository.findByShiftDateAndStatusIn(date, ON_SHIFT)
                    : daStatusRepository.findByCityIdAndShiftDateAndStatusIn(scopeCityId, date, ON_SHIFT);
        }
        List<UUID> daIds = onShift.stream().map(com.oneday.dispatch.domain.DaStatus::getDaId).toList();
        Map<UUID, DaDirectoryPort.DaContact> names = daDirectory.contactsFor(daIds);
        return daIds.stream()
                .map(daId -> {
                    int open = queueRepository.findByDaIdAndOperatingDateAndStatusIn(daId, date, ACTIVE).size();
                    DaDirectoryPort.DaContact c = names.get(daId);
                    return new DaRosterEntry(daId, c != null ? c.name() : null, open);
                })
                .sorted(Comparator.comparing(r -> r.daName() == null ? "" : r.daName()))
                .toList();
    }

    /**
     * The absent DAs' shift + its full DA roster for the date. Every requested DA must actually be
     * rostered to <em>this</em> city and date (a foreign-city or wrong-date id is rejected), and all
     * must share one shift (a DA belongs to exactly one; a mixed selection is rejected). The returned
     * id set scopes the M3 split to that shift — without it the split would mix the day's other shift
     * plan (same valid_date). {@code requireOnShift} additionally demands the DA be live on the clock —
     * true for the manager's preview (they pick from the on-shift roster); false for apply, where an
     * absent DA may already have dropped OFFLINE (that's the very thing being handled).
     */
    private Set<UUID> resolveShiftScope(UUID cityId, List<UUID> absentDaIds, LocalDate date,
                                        boolean requireOnShift) {
        String shiftType = null;
        for (UUID da : absentDaIds) {
            DaStatus st = daStatusRepository.findByDaId(da)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                            "DA " + da + " is not on shift for " + date));
            // Must be rostered to THIS city + date + a shift — never trust a stale/foreign row.
            if (!cityId.equals(st.getCityId()) || !date.equals(st.getShiftDate()) || st.getShiftType() == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "DA " + da + " is not on shift in this city for " + date);
            }
            if (requireOnShift && !ON_SHIFT.contains(st.getStatus())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "DA " + da + " is not currently on the clock");
            }
            if (shiftType == null) {
                shiftType = st.getShiftType();
            } else if (!shiftType.equals(st.getShiftType())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "All absent DAs must be on the same shift (got " + shiftType + " and " + st.getShiftType() + ")");
            }
        }
        // The roster for that shift IS the scope. Each validated absent DA is in it (same city+date+shift),
        // so we never widen the scope past the actual roster.
        return daStatusRepository.findByCityIdAndShiftDateAndShiftType(cityId, date, shiftType)
                .stream().map(DaStatus::getDaId).collect(Collectors.toCollection(HashSet::new));
    }

    @Override
    @Transactional
    public AbsencePreviewResponse preview(UUID cityId, List<UUID> daIds, String reason, UUID actorUserId) {
        LocalDate date = today();
        Set<UUID> inShiftDaIds = resolveShiftScope(cityId, daIds, date, true);
        AbsenceReassignmentPlan plan = gridService.planAbsenceReassignment(cityId, daIds, date, inShiftDaIds);

        List<ReceiverLoad> receivers = plan.reassignments().stream()
                .collect(Collectors.groupingBy(HexReassignment::toDaId, Collectors.counting()))
                .entrySet().stream()
                .map(e -> new ReceiverLoad(e.getKey(), e.getValue().intValue()))
                .sorted(Comparator.comparing(r -> r.daId().toString()))
                .toList();

        List<TaskMove> loose = new ArrayList<>();
        List<CustodyMove> custody = new ArrayList<>();
        List<TaskMove> orphans = new ArrayList<>();
        for (UUID absentDa : daIds) {
            for (DispatchQueue row : queueRepository.findByDaIdAndOperatingDateAndStatusIn(absentDa, date, ACTIVE)) {
                UUID newOwner = plan.ownerOf(row.getTileId());
                if (newOwner == null) {
                    orphans.add(new TaskMove(row.getShipmentId(), row.getOrderRef(),
                            row.getTaskType().name(), absentDa, null));
                } else if (row.getStatus() == TaskStatus.IN_PROGRESS) {
                    double[] at = collectLocation(absentDa, row);
                    custody.add(new CustodyMove(row.getShipmentId(), row.getOrderRef(),
                            absentDa, newOwner, at[0], at[1]));
                } else {
                    loose.add(new TaskMove(row.getShipmentId(), row.getOrderRef(),
                            row.getTaskType().name(), absentDa, newOwner));
                }
            }
        }

        // One live plan per DA: retire any still-PENDING plan in this city that overlaps these DAs, so a
        // re-preview can't leave a second plan whose auto-apply later races this one's apply on the same
        // territory (which would collide / land a confusing 0-count apply). The freshest preview wins.
        Set<UUID> theseDas = new HashSet<>(daIds);
        for (DaAbsenceEvent prior : absenceRepository.findByCityIdAndStatus(cityId, AbsenceStatus.PENDING)) {
            if (prior.absentDaIdList().stream().anyMatch(theseDas::contains)) {
                prior.setStatus(AbsenceStatus.CANCELLED);
                absenceRepository.save(prior);
            }
        }

        Instant autoApproveAt = Instant.now().plus(props.getAbsence().getAutoApproveTimeoutMinutes(),
                ChronoUnit.MINUTES);
        DaAbsenceEvent event = new DaAbsenceEvent();
        event.setCityId(cityId);
        event.setOperatingDate(date);
        event.setAbsentDaIdList(daIds);
        event.setReason(reason);
        event.setStatus(AbsenceStatus.PENDING);
        event.setCreatedBy(actorUserId);
        event.setAutoApproveAt(autoApproveAt);
        event.setOrphanCount(plan.orphanHexIds().size());
        UUID eventId = absenceRepository.save(event).getId();

        log.info("Absence preview {} cityId={} absent={} → {} hexes, {} loose, {} custody, {} orphan tasks",
                eventId, cityId, daIds.size(), plan.reassignments().size(),
                loose.size(), custody.size(), orphans.size());
        return new AbsencePreviewResponse(eventId, cityId, daIds.stream().sorted().toList(), autoApproveAt,
                plan.reassignments().size(), plan.orphanHexIds().size(), receivers, loose, custody, orphans);
    }

    @Override
    @Transactional
    public AbsenceApplyResponse apply(UUID eventId, UUID actorUserId, UUID scopeCityId) {
        DaAbsenceEvent event = loadEvent(eventId);
        // City scope first — a manager may only apply an absence event in their own city (404, not 403,
        // so a cross-city event id isn't confirmed to exist). ADMIN passes scopeCityId == null.
        if (scopeCityId != null && !scopeCityId.equals(event.getCityId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No absence event " + eventId);
        }
        return doApply(event, false, actorUserId);
    }

    @Override
    @Transactional
    public AbsenceApplyResponse autoApply(UUID eventId) {
        return doApply(loadEvent(eventId), true, null);
    }

    private DaAbsenceEvent loadEvent(UUID eventId) {
        return absenceRepository.findById(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No absence event " + eventId));
    }

    private AbsenceApplyResponse doApply(DaAbsenceEvent event, boolean system, UUID actorUserId) {
        if (event.getStatus() != AbsenceStatus.PENDING) {
            // Already applied/cancelled — idempotent.
            return new AbsenceApplyResponse(event.getId(), event.getStatus(), 0, 0, 0, event.getOrphanCount());
        }
        UUID cityId = event.getCityId();
        LocalDate date = event.getOperatingDate();
        List<UUID> daIds = event.absentDaIdList();
        UUID reviewer = system ? null : actorUserId;

        // 1) Commit the M3 territory split (writes + approves the INTRADAY_OVERRIDE), scoped to the
        //    absent DAs' shift so the day's other shift plan (same valid_date) never leaks in. Not
        //    requireOnShift: by apply time an absent DA may already have gone OFFLINE — expected.
        Set<UUID> inShiftDaIds = resolveShiftScope(cityId, daIds, date, false);
        AbsenceReassignmentPlan plan = gridService.applyAbsenceReassignment(cityId, daIds, date, inShiftDaIds, reviewer);

        // 2) Make every task follow its hex to the new owner.
        int moved = 0;
        int custody = 0;
        for (UUID absentDa : daIds) {
            for (DispatchQueue row : queueRepository.findByDaIdAndOperatingDateAndStatusIn(absentDa, date, ACTIVE)) {
                UUID newOwner = plan.ownerOf(row.getTileId());
                if (newOwner == null) {
                    row.setStatus(TaskStatus.DEFERRED);   // orphan hex — surfaced for manual escalation
                    queueRepository.save(row);
                    continue;
                }
                if (row.getStatus() == TaskStatus.IN_PROGRESS) {
                    double[] at = collectLocation(absentDa, row);
                    DispatchQueue collect = custodyCollectRow(newOwner, row, absentDa, at, date);
                    daStatusService.withDaLock(newOwner, () -> {
                        collect.setQueuePosition(nextPosition(newOwner, date));
                        queueRepository.save(collect);
                        QueueMirror.rebuild(daStatusService, queueRepository, newOwner, date);
                        return null;
                    });
                    custody++;
                } else {
                    DispatchQueue copy = followHexRow(newOwner, row, date);
                    daStatusService.withDaLock(newOwner, () -> {
                        copy.setQueuePosition(nextPosition(newOwner, date));
                        queueRepository.save(copy);
                        // Not the cron gate: place it, then let the reorder park it before / beyond the cron.
                        reorderService.reorder(newOwner, date);
                        return null;
                    });
                    moved++;
                }
                row.setStatus(TaskStatus.CANCELLED);   // vacate the absent DA's copy (append-only audit)
                queueRepository.save(row);
            }
        }

        // 3) Take the absent DAs offline and hand their territory to the receivers.
        for (UUID absentDa : daIds) {
            daStatusService.updateStatus(absentDa, DaStatusEnum.ABSENT);
            daStatusService.setTerritory(absentDa, List.of());
            QueueMirror.rebuild(daStatusService, queueRepository, absentDa, date);
        }
        Map<UUID, List<UUID>> territoryByDa = gridService.getActiveAssignments(cityId, date).stream()
                .collect(Collectors.groupingBy(AssignmentResponse::daId,
                        Collectors.mapping(AssignmentResponse::hexId, Collectors.toList())));
        Set<UUID> receivers = plan.reassignments().stream()
                .map(HexReassignment::toDaId).collect(Collectors.toSet());
        for (UUID receiver : receivers) {
            daStatusService.setTerritory(receiver, territoryByDa.getOrDefault(receiver, List.of()));
        }

        event.setStatus(system ? AbsenceStatus.AUTO_APPLIED : AbsenceStatus.APPLIED);
        event.setAppliedAt(Instant.now());
        absenceRepository.save(event);

        log.info("Absence applied {} ({}): {} hexes, {} tasks moved, {} custody handoffs, {} orphan hexes",
                event.getId(), event.getStatus(), plan.reassignments().size(), moved, custody,
                plan.orphanHexIds().size());
        return new AbsenceApplyResponse(event.getId(), event.getStatus(),
                plan.reassignments().size(), moved, custody, plan.orphanHexIds().size());
    }

    /** A not-yet-collected task, re-created for the hex's new owner (append-only; the old row is cancelled). */
    private DispatchQueue followHexRow(UUID newOwner, DispatchQueue src, LocalDate date) {
        DispatchQueue r = baseRow(newOwner, src, date);
        r.setTaskType(src.getTaskType());
        r.setTaskLat(src.getTaskLat());
        r.setTaskLon(src.getTaskLon());
        return r;
    }

    /** A CUSTODY_COLLECT task: the new owner collects the in-custody parcel from the absent DA. */
    private DispatchQueue custodyCollectRow(UUID newOwner, DispatchQueue src, UUID absentDa,
                                            double[] at, LocalDate date) {
        DispatchQueue r = baseRow(newOwner, src, date);
        r.setTaskType(TaskType.CUSTODY_COLLECT);
        r.setTaskLat(at[0]);           // where to meet the absent DA (their last GPS / the task location)
        r.setTaskLon(at[1]);
        r.setCollectFromDaId(absentDa);
        // Carry the original leg + its destination so the onward task can resume once collected.
        r.setOnwardTaskType(src.getTaskType());
        r.setOnwardTaskLat(src.getTaskLat());
        r.setOnwardTaskLon(src.getTaskLon());
        return r;
    }

    private DispatchQueue baseRow(UUID newOwner, DispatchQueue src, LocalDate date) {
        DispatchQueue r = new DispatchQueue();
        r.setDaId(newOwner);
        r.setCityId(src.getCityId());
        r.setShipmentId(src.getShipmentId());
        r.setOrderId(src.getOrderId());
        r.setOrderRef(src.getOrderRef());
        r.setTileId(src.getTileId());
        r.setHomeTileId(src.getHomeTileId());
        r.setStatus(TaskStatus.QUEUED);
        r.setPaymentMode(src.getPaymentMode());
        r.setCrossTerritory(false);
        r.setCronSafe(false);
        r.setBeyondCron(false);
        r.setPickedUp(false);
        r.setAssignedAt(Instant.now());
        r.setOperatingDate(date);
        return r;
    }

    private int nextPosition(UUID daId, LocalDate date) {
        return queueRepository.findByDaIdAndOperatingDateAndStatusIn(daId, date, ACTIVE).stream()
                .mapToInt(DispatchQueue::getQueuePosition).max().orElse(-1) + 1;
    }

    /** Where the new owner collects a parcel: the absent DA's last GPS, else the task's own location. */
    private double[] collectLocation(UUID absentDa, DispatchQueue row) {
        DaLiveStatus live = daStatusService.getLiveStatus(absentDa);
        if (live != null && live.getLat() != null && live.getLon() != null) {
            return new double[] {live.getLat(), live.getLon()};
        }
        return new double[] {row.getTaskLat(), row.getTaskLon()};
    }

    private LocalDate today() {
        return LocalDate.now(ZoneId.of(props.getShift().getZone()));
    }
}
