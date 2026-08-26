package com.oneday.dispatch.service.impl;

import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.domain.CronAssignmentStatus;
import com.oneday.dispatch.domain.DaCronAssignment;
import com.oneday.dispatch.domain.DaStatusEnum;
import com.oneday.dispatch.domain.DispatchQueue;
import com.oneday.dispatch.domain.TaskStatus;
import com.oneday.dispatch.domain.TaskType;
import com.oneday.dispatch.events.DaEventProducer;
import com.oneday.dispatch.events.HubScanSeamProducer;
import com.oneday.dispatch.repository.DaCronAssignmentRepository;
import com.oneday.dispatch.repository.DispatchQueueRepository;
import com.oneday.dispatch.service.DaStatusService;
import com.oneday.dispatch.service.DaTaskService;
import com.oneday.dispatch.service.DaTaskView;
import com.oneday.dispatch.service.model.DaQueue;
import com.oneday.common.domain.MeetingMode;
import com.oneday.common.log.AuditLog;
import com.oneday.common.port.CityMeetingModePort;
import com.oneday.common.port.ShipmentContactPort;
import com.oneday.common.port.ShipmentContactPort.ShipmentContact;
import com.oneday.common.port.ShipmentRefPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Implements the DA task-lifecycle transitions (design §6). Each op runs under the DA's lock, guards
 * ownership (404) and the legal status transition (409), persists the change, refreshes the in-memory
 * mirror, and emits the matching (gated) DA lifecycle event.
 */
@Service
class DaTaskServiceImpl implements DaTaskService {

    private static final Logger log = LoggerFactory.getLogger(DaTaskServiceImpl.class);

    private final DispatchQueueRepository queueRepository;
    private final DaCronAssignmentRepository cronRepository;
    private final DaStatusService daStatusService;
    private final DaEventProducer daEventProducer;
    private final DispatchProperties props;
    private final HubScanSeamProducer hubScanSeamProducer;
    private final ShipmentRefPort shipmentRefPort;
    private final ShipmentContactPort shipmentContactPort;
    private final QueueReorderService queueReorderService;
    private final CityMeetingModePort meetingModePort;

    DaTaskServiceImpl(DispatchQueueRepository queueRepository,
                      DaCronAssignmentRepository cronRepository,
                      DaStatusService daStatusService,
                      DaEventProducer daEventProducer,
                      DispatchProperties props,
                      HubScanSeamProducer hubScanSeamProducer,
                      ShipmentRefPort shipmentRefPort,
                      ShipmentContactPort shipmentContactPort,
                      QueueReorderService queueReorderService,
                      CityMeetingModePort meetingModePort) {
        this.queueRepository = queueRepository;
        this.cronRepository = cronRepository;
        this.daStatusService = daStatusService;
        this.daEventProducer = daEventProducer;
        this.props = props;
        this.hubScanSeamProducer = hubScanSeamProducer;
        this.queueReorderService = queueReorderService;
        this.shipmentRefPort = shipmentRefPort;
        this.shipmentContactPort = shipmentContactPort;
        this.meetingModePort = meetingModePort;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DaTaskView> listTasks(UUID daId, LocalDate date) {
        LocalDate day = date != null ? date : LocalDate.now(ZoneId.of(props.getShift().getZone()));
        List<DispatchQueue> rows = queueRepository.findByDaIdAndOperatingDateOrderByQueuePosition(daId, day);
        List<UUID> shipmentIds = rows.stream().map(DispatchQueue::getShipmentId).toList();
        Map<UUID, String> refs = shipmentRefPort.refsFor(shipmentIds);
        Map<UUID, ShipmentContact> contacts = shipmentContactPort.contactsFor(shipmentIds);
        return rows.stream()
                .map(r -> DaTaskView.of(r, refs.get(r.getShipmentId()), contacts.get(r.getShipmentId())))
                .toList();
    }

    @Override
    @Transactional
    public DaTaskView markEnRoute(UUID daId, UUID taskId) {
        return daStatusService.withDaLock(daId, () -> {
            DispatchQueue task = ownedTask(daId, taskId);
            requireType(task, TaskType.PICKUP);
            requireStatus(task, TaskStatus.QUEUED);
            task.setStatus(TaskStatus.IN_PROGRESS);
            task.setStartedAt(Instant.now());
            return save(task);
        });
    }

    @Override
    @Transactional
    public DaTaskView markArrivedAtStop(UUID daId, UUID taskId) {
        return daStatusService.withDaLock(daId, () -> {
            DispatchQueue task = ownedTask(daId, taskId);
            // Stamp once — a resumed screen re-tapping "Mark arrived" must not overwrite the first arrival.
            if (task.getArrivedAt() == null) {
                task.setArrivedAt(Instant.now());
                DaTaskView view = save(task);
                AuditLog.event("da.arrived_at_stop")
                        .kv("taskId", taskId)
                        .kv("shipmentId", task.getShipmentId())
                        .kv("taskType", task.getTaskType())
                        .kv("daId", daId)
                        .log();
                return view;
            }
            return DaTaskView.of(task);
        });
    }

    @Override
    @Transactional
    public DaTaskView recordVanHandoff(UUID daId, UUID taskId, List<String> parcelScans, UUID vanId) {
        return completePickupHandoff(daId, taskId, parcelScans, task -> {
            daEventProducer.emitVanHandoffCompleted(daId, task.getCityId(), task.getShipmentId());
            log.debug("Van handoff: task {} (van {}) completed with {} scan(s)", taskId, vanId, parcelScans.size());
        });
    }

    @Override
    @Transactional
    public DaTaskView recordHubHandoff(UUID daId, UUID taskId, List<String> parcelScans) {
        // HUB_RETURN city (no van): the DA drops the collected pickups AT the hub. This is a custody
        // CLAIM (task done → RETURNED_TO_HUB, "handed to transport") — NOT the hub arrival. The hub
        // operator's dock scan (M7 receive → HUB_ORIGIN_IN) is what advances the parcel to AT_ORIGIN_HUB,
        // so a DA can't forge arrival by tapping a button.
        requireHubReturnCity(daId, taskId);
        return completePickupHandoff(daId, taskId, parcelScans, task -> {
            daEventProducer.emitHubReturnHandoffCompleted(daId, task.getCityId(), task.getShipmentId());
            AuditLog.event("da.hub_handoff")
                    .kv("daId", daId)
                    .kv("taskId", taskId)
                    .kv("shipmentId", task.getShipmentId())
                    .kv("parcelScans", parcelScans.size())
                    .log();
            log.debug("Hub handoff: task {} completed with {} scan(s)", taskId, parcelScans.size());
        });
    }

    /**
     * Shared PICKUP handoff at a rendezvous (van meeting point or hub): validate scans, complete the
     * task, roll the cron handoff, then run {@code onComplete} for the mode-specific event/scan emission.
     */
    private DaTaskView completePickupHandoff(UUID daId, UUID taskId, List<String> parcelScans,
                                             java.util.function.Consumer<DispatchQueue> onComplete) {
        if (parcelScans == null || parcelScans.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "At least one parcel scan is required");
        }
        return daStatusService.withDaLock(daId, () -> {
            DispatchQueue task = ownedTask(daId, taskId);
            requireType(task, TaskType.PICKUP);
            requireStatus(task, TaskStatus.IN_PROGRESS);
            task.setStatus(TaskStatus.COMPLETED);
            task.setCompletedAt(Instant.now());
            DaTaskView view = save(task);
            recordCronHandoff(daId, task.getOperatingDate(), parcelScans.size());
            onComplete.accept(task);
            // Head removed → re-rank the remaining tail against the new head (cron-aware).
            queueReorderService.reorder(daId, task.getOperatingDate());
            return view;
        });
    }

    @Override
    @Transactional
    public DaTaskView markFailed(UUID daId, UUID taskId, String reason) {
        return daStatusService.withDaLock(daId, () -> {
            DispatchQueue task = ownedTask(daId, taskId);
            requireActive(task);
            task.setStatus(TaskStatus.FAILED);
            task.setCompletedAt(Instant.now());
            DaTaskView view = save(task);
            // A custody collect is a pickup-shaped custody take → PICKUP_FAILED (not DROP_FAILED),
            // so M11 triages a failed hand-off correctly rather than as a delivery miss.
            if (task.getTaskType() == TaskType.DELIVERY) {
                daEventProducer.emitDropFailed(daId, task.getCityId(), task.getShipmentId(), reason);
            } else {
                daEventProducer.emitPickupFailed(daId, task.getCityId(), task.getShipmentId(), reason);
            }
            return view;
        });
    }

    @Override
    @Transactional
    public DaTaskView reattempt(UUID daId, UUID taskId) {
        return daStatusService.withDaLock(daId, () -> {
            DispatchQueue task = ownedTask(daId, taskId);
            requireStatus(task, TaskStatus.FAILED);
            // Re-queue at the end of the DA's own list so current work continues first, then this retry.
            int endPos = queueRepository
                    .findByDaIdAndOperatingDateOrderByQueuePosition(daId, task.getOperatingDate()).stream()
                    .mapToInt(DispatchQueue::getQueuePosition).max().orElse(task.getQueuePosition());
            task.setStatus(TaskStatus.QUEUED);
            task.setQueuePosition(endPos + 1);
            task.setStartedAt(null);
            task.setCompletedAt(null);
            DaTaskView view = save(task);
            daEventProducer.emitQueueReordered(daId, task.getCityId());
            return view;
        });
    }

    @Override
    @Transactional
    public DaTaskView markDropCollected(UUID daId, UUID taskId) {
        return collectDelivery(daId, taskId, task -> { /* van pickup from the loop — no extra scan */ });
    }

    @Override
    @Transactional
    public DaTaskView recordHubCollect(UUID daId, UUID taskId) {
        // HUB_RETURN city (no van): the DA collects the delivery FROM the hub for last-mile.
        requireHubReturnCity(daId, taskId);
        return collectDelivery(daId, taskId, task ->
                // M8-SEAM: hub-dest custody scan (ledger only — the DA's later DROP_* events drive state).
                hubScanSeamProducer.emitHubDestOut(task.getShipmentId()));
    }

    /**
     * Shared DELIVERY collect (from a van loop or the hub): QUEUED → IN_PROGRESS, emit DROP_COLLECTED,
     * then run {@code onCollected} for any mode-specific scan.
     */
    private DaTaskView collectDelivery(UUID daId, UUID taskId,
                                       java.util.function.Consumer<DispatchQueue> onCollected) {
        return daStatusService.withDaLock(daId, () -> {
            DispatchQueue task = ownedTask(daId, taskId);
            requireType(task, TaskType.DELIVERY);
            requireStatus(task, TaskStatus.QUEUED);
            task.setStatus(TaskStatus.IN_PROGRESS);
            task.setStartedAt(Instant.now());
            DaTaskView view = save(task);
            daEventProducer.emitDropCollected(daId, task.getCityId(), task.getShipmentId());
            onCollected.accept(task);
            return view;
        });
    }

    @Override
    @Transactional
    public DaTaskView markDropCompleted(UUID daId, UUID taskId, boolean codCollected) {
        return daStatusService.withDaLock(daId, () -> {
            DispatchQueue task = ownedTask(daId, taskId);
            requireType(task, TaskType.DELIVERY);
            requireStatus(task, TaskStatus.IN_PROGRESS);
            task.setStatus(TaskStatus.COMPLETED);
            task.setCompletedAt(Instant.now());
            DaTaskView view = save(task);
            daEventProducer.emitDropCompleted(daId, task.getCityId(), task.getShipmentId());
            if (codCollected) {
                daEventProducer.emitCodCollected(daId, task.getCityId(), task.getShipmentId());
            }
            // Head removed → re-rank the remaining tail against the new head (cron-aware).
            queueReorderService.reorder(daId, task.getOperatingDate());
            return view;
        });
    }

    @Override
    @Transactional
    public DaTaskView recordCustodyCollect(UUID daId, UUID taskId, List<String> parcelScans) {
        if (parcelScans == null || parcelScans.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "At least one parcel scan is required");
        }
        return daStatusService.withDaLock(daId, () -> {
            DispatchQueue task = ownedTask(daId, taskId);
            requireType(task, TaskType.CUSTODY_COLLECT);
            requireCollectable(task);
            requireScanMatchesShipment(task, parcelScans);   // the scan must be THIS parcel's label
            task.setStatus(TaskStatus.COMPLETED);
            if (task.getStartedAt() == null) {
                task.setStartedAt(Instant.now());
            }
            task.setCompletedAt(Instant.now());
            DaTaskView view = save(task);
            // M8-SEAM (best-effort): append the DA→DA custody scan to the ledger. The authoritative record
            // that custody moved is the committed dispatch_queue transition above (this task COMPLETED +
            // the onward leg) — the scan is a ledger bridge until M8 owns it, so a publish hiccup must
            // never block the hand-off.
            hubScanSeamProducer.emitDaCustodyTransfer(task.getShipmentId());
            daEventProducer.emitCustodyCollected(daId, task.getCityId(), task.getShipmentId(),
                    task.getCollectFromDaId());
            // The parcel is now in this DA's hands → resume its onward leg on this DA (in-hand).
            spawnOnwardLeg(daId, task);
            AuditLog.event("da.custody_collected")
                    .kv("daId", daId)
                    .kv("taskId", taskId)
                    .kv("shipmentId", task.getShipmentId())
                    .kv("fromDaId", task.getCollectFromDaId())
                    .kv("onwardTaskType", task.getOnwardTaskType())
                    .log();
            // Collect head removed + onward leg inserted → re-rank the tail (cron-aware).
            queueReorderService.reorder(daId, task.getOperatingDate());
            return view;
        });
    }

    /**
     * Re-create the in-custody parcel's onward leg (its original PICKUP/DELIVERY) for the covering DA,
     * IN_PROGRESS because the parcel is already in hand — no re-collect step. The onward type and its
     * destination were captured on the CUSTODY_COLLECT row when the absence was applied.
     */
    private void spawnOnwardLeg(UUID daId, DispatchQueue collect) {
        TaskType onwardType = collect.getOnwardTaskType();
        if (onwardType == null || onwardType == TaskType.CUSTODY_COLLECT) {
            return;   // nothing to resume (defensive — a well-formed collect row always carries an onward leg)
        }
        DispatchQueue onward = new DispatchQueue();
        onward.setDaId(daId);
        onward.setCityId(collect.getCityId());
        onward.setShipmentId(collect.getShipmentId());
        onward.setOrderId(collect.getOrderId());
        onward.setOrderRef(collect.getOrderRef());
        onward.setTaskType(onwardType);
        onward.setTaskLat(collect.getOnwardTaskLat() != null ? collect.getOnwardTaskLat() : collect.getTaskLat());
        onward.setTaskLon(collect.getOnwardTaskLon() != null ? collect.getOnwardTaskLon() : collect.getTaskLon());
        onward.setTileId(collect.getTileId());
        onward.setHomeTileId(collect.getHomeTileId());
        onward.setPaymentMode(collect.getPaymentMode());
        onward.setStatus(TaskStatus.IN_PROGRESS);        // parcel already in hand
        onward.setPickedUp(onwardType == TaskType.PICKUP);
        onward.setCrossTerritory(false);
        onward.setCronSafe(false);
        onward.setBeyondCron(false);
        onward.setStartedAt(Instant.now());
        onward.setAssignedAt(Instant.now());
        onward.setOperatingDate(collect.getOperatingDate());
        onward.setQueuePosition(nextPosition(daId, collect.getOperatingDate()));
        queueRepository.save(onward);
    }

    private int nextPosition(UUID daId, LocalDate date) {
        return queueRepository.findByDaIdAndOperatingDateOrderByQueuePosition(daId, date).stream()
                .mapToInt(DispatchQueue::getQueuePosition).max().orElse(-1) + 1;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private DispatchQueue ownedTask(UUID daId, UUID taskId) {
        DispatchQueue task = queueRepository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such task " + taskId));
        if (!task.getDaId().equals(daId)) {
            // Don't leak another DA's task — treat as not found for this DA.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such task " + taskId + " for DA " + daId);
        }
        return task;
    }

    private DaTaskView save(DispatchQueue task) {
        queueRepository.save(task);
        QueueMirror.rebuild(daStatusService, queueRepository, task.getDaId(), task.getOperatingDate());
        return DaTaskView.of(task);
    }

    private void recordCronHandoff(UUID daId, LocalDate date, int parcelCount) {
        cronRepository.findByDaIdAndOperatingDate(daId, date).ifPresent(cron -> {
            cron.setHandoffCompletedAt(Instant.now());
            int prior = cron.getParcelCountHanded() != null ? cron.getParcelCountHanded() : 0;
            cron.setParcelCountHanded(prior + parcelCount);

            // HUB_RETURN crons carry no van and recur through the day (M6 gate off). A hub drop that
            // still has a later return today only COMPLETES this leg: roll the meeting to the next slot
            // and stay SCHEDULED so the hard constraint keeps gating, and free the DA to work until then.
            // The last return (no later slot) — and every van rendezvous (v1, single meeting) — is terminal.
            Instant next = (cron.getVanId() == null) ? nextSlotAfter(cron, cron.getScheduledMeetingTime()) : null;
            if (next != null) {
                cron.setScheduledMeetingTime(next);
                cron.setStatus(CronAssignmentStatus.SCHEDULED);
                cronRepository.save(cron);
                refreshMemCron(daId, next);
                if (daStatusService.getStatus(daId) == DaStatusEnum.AT_CRON) {
                    daStatusService.updateStatus(daId, DaStatusEnum.IDLE);
                }
            } else {
                cron.setStatus(CronAssignmentStatus.COMPLETED);
                cronRepository.save(cron);
            }
        });
    }

    /** First periodic meeting strictly after {@code reference} (the just-completed slot); null if none left. */
    private Instant nextSlotAfter(DaCronAssignment cron, Instant reference) {
        if (cron.getMeetingTimes() == null || cron.getMeetingTimes().isEmpty() || reference == null) {
            return null;
        }
        ZoneId zone = ZoneId.of(props.getShift().getZone());
        return cron.getMeetingTimes().stream()
                .map(LocalTime::parse)
                .map(t -> LocalDateTime.of(cron.getOperatingDate(), t).atZone(zone).toInstant())
                .filter(i -> i.isAfter(reference))
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    /** Keep the in-memory queue's cron in step so CronMonitorJob re-freezes ahead of the next return. */
    private void refreshMemCron(UUID daId, Instant nextMeeting) {
        DaQueue q = daStatusService.getQueue(daId);
        if (q != null && q.getCron() != null) {
            q.getCron().setScheduledMeetingTime(nextMeeting);
            q.getCron().setStatus(CronAssignmentStatus.SCHEDULED);
        }
    }

    /**
     * The hub-handoff / hub-collect ops are only valid in a HUB_RETURN city. Resolve the mode from the
     * TASK's city (authoritative, always present) — not the in-memory DA live status, which can be null
     * after a restart and previously NPE'd here (→ 500). A non-HUB_RETURN city → 409.
     */
    private void requireHubReturnCity(UUID daId, UUID taskId) {
        DispatchQueue task = ownedTask(daId, taskId);
        if (meetingModePort.modeFor(task.getCityId()) != MeetingMode.HUB_RETURN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Hub handoff/collect is only valid in a HUB_RETURN city");
        }
    }

    private static void requireType(DispatchQueue task, TaskType expected) {
        if (task.getTaskType() != expected) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Task " + task.getId() + " is a " + task.getTaskType() + ", not a " + expected);
        }
    }

    private static void requireStatus(DispatchQueue task, TaskStatus expected) {
        if (task.getStatus() != expected) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Task " + task.getId() + " is " + task.getStatus() + ", expected " + expected);
        }
    }

    private static void requireActive(DispatchQueue task) {
        if (task.getStatus() != TaskStatus.QUEUED && task.getStatus() != TaskStatus.IN_PROGRESS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Task " + task.getId() + " is " + task.getStatus() + " and cannot be failed");
        }
    }

    /**
     * The custody hand-off must prove the right parcel was taken: at least one scan must be this
     * shipment's label barcode (== its ref; v1 parcelId == shipmentId). Guards against a DA completing
     * a custody transfer with a junk scan. Skipped only when the shipment has no ref to check against.
     */
    private void requireScanMatchesShipment(DispatchQueue task, List<String> parcelScans) {
        String ref = shipmentRefPort.refsFor(List.of(task.getShipmentId())).get(task.getShipmentId());
        if (ref == null) {
            return;   // no barcode/ref to validate against — the non-empty cardinality guard still applied
        }
        boolean matched = parcelScans.stream()
                .anyMatch(s -> s != null && s.trim().equalsIgnoreCase(ref.trim()));
        if (!matched) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Scanned parcel does not match this task's shipment " + ref);
        }
    }

    /** A CUSTODY_COLLECT can be confirmed straight from QUEUED (no separate en-route step) or IN_PROGRESS. */
    private static void requireCollectable(DispatchQueue task) {
        if (task.getStatus() != TaskStatus.QUEUED && task.getStatus() != TaskStatus.IN_PROGRESS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Task " + task.getId() + " is " + task.getStatus() + " and cannot be collected");
        }
    }
}
