package com.oneday.exceptions.service.impl;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.exceptions.config.ExceptionProperties;
import com.oneday.exceptions.domain.Disposition;
import com.oneday.exceptions.domain.ExceptionAction;
import com.oneday.exceptions.domain.ExceptionCase;
import com.oneday.exceptions.domain.ExceptionReason;
import com.oneday.exceptions.domain.ExceptionStatus;
import com.oneday.exceptions.domain.ExceptionType;
import com.oneday.exceptions.domain.ResolveAction;
import com.oneday.exceptions.dto.BatchResolveRequest;
import com.oneday.exceptions.dto.BatchResolveResponse;
import com.oneday.exceptions.dto.ExceptionActionView;
import com.oneday.exceptions.dto.ExceptionCaseDetail;
import com.oneday.exceptions.dto.ExceptionCaseSummary;
import com.oneday.exceptions.dto.ExceptionQueueResponse;
import com.oneday.exceptions.dto.ExceptionSummaryResponse;
import com.oneday.exceptions.events.ExceptionEventProducer;
import com.oneday.common.port.CourierOnShipmentPort;
import com.oneday.common.port.ShipmentContactPort;
import com.oneday.exceptions.repository.ExceptionActionRepository;
import com.oneday.exceptions.repository.ExceptionCaseRepository;
import com.oneday.exceptions.service.ExceptionCaseService;
import com.oneday.orders.service.ShipmentJourneyService;
import com.oneday.orders.service.ShipmentLookupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
class ExceptionCaseServiceImpl implements ExceptionCaseService {

    private static final Logger log = LoggerFactory.getLogger(ExceptionCaseServiceImpl.class);

    private static final int MAX_PAGE_SIZE = 100;
    // Roles allowed to trigger an intraday dispatch change (REASSIGN) — ops desk, not call-centre.
    private static final String ADMIN = "ADMIN";
    private static final String STATION_MANAGER = "STATION_MANAGER";
    private static final String SUPERVISOR = "SUPERVISOR";

    private final ExceptionCaseRepository caseRepo;
    private final ExceptionActionRepository actionRepo;
    private final ShipmentLookupService shipmentLookup;
    private final ShipmentJourneyService journeyService;
    private final CourierOnShipmentPort courierPort;
    private final ShipmentContactPort contactPort;
    private final ExceptionEventProducer producer;
    private final ExceptionProperties props;
    /** Self-proxy so batchResolve's per-id resolve() calls cross the @Transactional boundary (a direct
     *  this.resolve() would bypass the proxy → no per-case transaction). @Lazy breaks the self-cycle. */
    private final ExceptionCaseService self;

    public ExceptionCaseServiceImpl(ExceptionCaseRepository caseRepo, ExceptionActionRepository actionRepo,
                                    ShipmentLookupService shipmentLookup, ShipmentJourneyService journeyService,
                                    CourierOnShipmentPort courierPort, ShipmentContactPort contactPort,
                                    ExceptionEventProducer producer, ExceptionProperties props,
                                    @Lazy ExceptionCaseService self) {
        this.self = self;
        this.caseRepo = caseRepo;
        this.actionRepo = actionRepo;
        this.shipmentLookup = shipmentLookup;
        this.journeyService = journeyService;
        this.courierPort = courierPort;
        this.contactPort = contactPort;
        this.producer = producer;
        this.props = props;
    }

    // ── Capture ───────────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void captureDaFailure(UUID shipmentId, String shipmentRef, ExceptionType type,
                                 ExceptionReason reason, boolean daAttributable) {
        if (shipmentId == null) {
            return; // DA-scoped event with no shipment (e.g. a fleet-wide cron miss) — nothing to case yet.
        }
        Optional<ExceptionCase> live = caseRepo.findFirstByShipmentIdAndResolvedAtIsNull(shipmentId);
        if (live.isPresent() && live.get().getStatus() == ExceptionStatus.RTO) {
            // Already returning to origin — a failure on the return leg isn't a fresh delivery attempt,
            // so don't reset the case to OPEN/REATTEMPTABLE. Just record it on the trail.
            logAction(live.get().getId(), "FAILURE_CAPTURED", "system", null,
                    "failure during RTO" + (reason != null ? " · " + reason : ""));
            return;
        }
        ExceptionCase c = live.orElseGet(() -> openCase(shipmentId, shipmentRef, type));
        if (live.isPresent()) {
            // A repeat failure on the same live case → another attempt; re-open it for action.
            c.setAttemptNo(c.getAttemptNo() + 1);
            c.setStatus(ExceptionStatus.OPEN);
            c.setType(type);
        }
        if (reason != null && reason != ExceptionReason.UNKNOWN) {
            c.setReasonCode(reason);
        }
        if (daAttributable) {
            c.setDaAttributable(true);
        }
        c.setDisposition(dispositionFor(c));
        caseRepo.save(c);
        logAction(c.getId(), "FAILURE_CAPTURED", "system", null,
                "attempt " + c.getAttemptNo() + (reason != null ? " · " + reason : ""));
    }

    private ExceptionCase openCase(UUID shipmentId, String shipmentRef, ExceptionType type) {
        ExceptionCase c = new ExceptionCase();
        c.setShipmentId(shipmentId);
        c.setShipmentRef(shipmentRef);
        c.setType(type);
        c.setStatus(ExceptionStatus.OPEN);
        c.setAttemptNo(1);
        c.setOpenedAt(Instant.now());
        // Enrich routing facts from M4 (the failure event doesn't carry cities / delivery type).
        if (shipmentRef != null) {
            shipmentLookup.findByRef(shipmentRef).ifPresent(info -> {
                c.setOriginCity(info.originCity());
                c.setDestCity(info.destCity());
                c.setDeliveryType(info.deliveryType());
                c.setOrderId(info.orderId());
                c.setOrderRef(info.orderRef());
            });
        }
        return c;
    }

    @Override
    @Transactional
    public void onShipmentStateChanged(UUID shipmentId, ShipmentState toState) {
        Optional<ExceptionCase> live = caseRepo.findFirstByShipmentIdAndResolvedAtIsNull(shipmentId);
        if (live.isEmpty()) {
            return; // No open case — nothing to advance (pickup/delivery failures open via the DA path).
        }
        ExceptionCase c = live.get();
        switch (toState) {
            case RTO_INITIATED -> {
                c.setStatus(ExceptionStatus.RTO);
                c.setDisposition(Disposition.RETURNED);
                caseRepo.save(c);
            }
            case RTO_COMPLETED -> close(c, ExceptionStatus.RTO, Disposition.RETURNED);
            // A successful terminal delivery clears any lingering case (e.g. a reschedule that landed).
            case DROPPED, HUB_COLLECTED -> close(c, ExceptionStatus.RESOLVED, Disposition.RESOLVED);
            // Shipment cancelled (M4 allows it from PICKUP_FAILED, which opened this case) — close the
            // case so it doesn't sit OPEN forever, and so no agent can later drive an illegal RTO from it.
            case CANCELLED -> close(c, ExceptionStatus.CANCELLED, c.getDisposition());
            default -> { /* other transitions don't affect the case */ }
        }
    }

    private void close(ExceptionCase c, ExceptionStatus status, Disposition disposition) {
        c.setStatus(status);
        c.setDisposition(disposition);
        c.setResolvedAt(Instant.now());
        caseRepo.save(c);
    }

    /** Attempt policy (R2/F1): pickup/delivery gets {@code maxReattempts} re-tries before it's flagged
     *  UNDELIVERABLE (recommend RTO). Cron/flight-missed aren't attempt-based — they stay reattemptable. */
    private Disposition dispositionFor(ExceptionCase c) {
        if (c.getType() == ExceptionType.CRON_MISSED || c.getType() == ExceptionType.FLIGHT_MISSED) {
            return Disposition.REATTEMPTABLE;
        }
        int reattemptsUsed = c.getAttemptNo() - 1;
        return reattemptsUsed >= props.maxReattempts() ? Disposition.UNDELIVERABLE : Disposition.REATTEMPTABLE;
    }

    // ── Queries ───────────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public ExceptionQueueResponse queue(String cityScope, ExceptionType type, int page, int size) {
        int p = Math.max(0, page);
        int s = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        Page<ExceptionCase> result = caseRepo.queue(cityScope, type, PageRequest.of(p, s));
        List<ExceptionCaseSummary> items = result.getContent().stream().map(ExceptionCaseSummary::from).toList();
        return new ExceptionQueueResponse(p, s, result.getTotalElements(), items);
    }

    @Override
    @Transactional(readOnly = true)
    public ExceptionSummaryResponse summary(String cityScope) {
        long open = 0, reattemptable = 0, undeliverable = 0, returned = 0, missing = 0;
        for (Object[] row : caseRepo.countOpenByDisposition(cityScope)) {
            Disposition d = (Disposition) row[0];
            long n = (Long) row[1];
            open += n;
            switch (d) {
                case REATTEMPTABLE -> reattemptable += n;
                case UNDELIVERABLE -> undeliverable += n;
                case RETURNED -> returned += n;
                case MISSING -> missing += n;
                case RESOLVED -> { /* resolved cases aren't in the live set */ }
            }
        }
        return new ExceptionSummaryResponse(open, reattemptable, undeliverable, returned, missing);
    }

    @Override
    @Transactional(readOnly = true)
    public ExceptionCaseDetail detail(UUID caseId, String cityScope) {
        ExceptionCase c = caseRepo.findById(caseId)
                .filter(x -> visible(x, cityScope))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found"));
        List<ExceptionActionView> actions = actionRepo.findByCaseIdOrderByCreatedAtAsc(caseId).stream()
                .map(ExceptionActionView::from).toList();
        // The full picture: the shipment's whole internal trail + who to call at the failed stage.
        List<com.oneday.orders.dto.JourneyStep> journey = journeyService.journey(c.getShipmentId());
        ExceptionCaseDetail.Contact handler = courierPort.forShipment(c.getShipmentId())
                .map(x -> new ExceptionCaseDetail.Contact(x.name(), x.phone(), x.role().name())).orElse(null);
        ShipmentContactPort.ShipmentContact contact =
                contactPort.contactsFor(List.of(c.getShipmentId())).get(c.getShipmentId());
        ExceptionCaseDetail.Contact receiver = contact == null ? null
                : new ExceptionCaseDetail.Contact(contact.receiverName(), contact.receiverPhone(), "CUSTOMER");
        long siblings = c.getOrderRef() == null ? 0
                : caseRepo.countByOrderRefAndResolvedAtIsNull(c.getOrderRef());
        return new ExceptionCaseDetail(ExceptionCaseSummary.from(c), actions, journey, handler, receiver, siblings);
    }

    // ── Resolve ───────────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void resolve(UUID caseId, ResolveAction action, String cityScope,
                        String userId, String role, String notes) {
        // Authorize the restricted action BEFORE any lookup, so an unauthorized caller can't probe case
        // existence/state (404/409) through this endpoint. Reassigning to a new DA re-runs M5 dispatch —
        // an intraday change that needs ops (station-manager) approval, not a call-centre action.
        if (action == ResolveAction.REASSIGN_DELIVERY
                && !ADMIN.equals(role) && !STATION_MANAGER.equals(role) && !SUPERVISOR.equals(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "REASSIGN_DELIVERY requires station-manager approval");
        }
        ExceptionCase c = caseRepo.findById(caseId)
                .filter(x -> visible(x, cityScope))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found"));
        if (c.getResolvedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Case is already closed");
        }

        // Drive M4 (and, downstream, M10) by publishing the matching event — the orders consumer transitions.
        // Publish only AFTER this tx commits (matches orders' ShipmentEventProducer): the broker send is
        // synchronous and swallows errors, so publishing inline would let M4 act on an event a later
        // rollback erases — a permanent M11/M4 divergence with no audit row.
        if (action.event() != null) {
            var evt = action.event();
            var sid = c.getShipmentId();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { producer.publish(sid, evt); }
            });
        }

        c.setResolution(action);
        c.setAssignedTo(userId);
        c.setAssignedRole(role);
        if (notes != null && !notes.isBlank()) {
            c.setNotes(notes);
        }
        switch (action) {
            case RESCHEDULE_PICKUP, RESCHEDULE_DELIVERY, REASSIGN_DELIVERY -> {
                c.setStatus(ExceptionStatus.RESCHEDULED);
                caseRepo.save(c);
            }
            case INITIATE_RTO -> {
                c.setStatus(ExceptionStatus.RTO);
                c.setDisposition(Disposition.RETURNED);
                caseRepo.save(c);
            }
            case COMPLETE_RTO -> close(c, ExceptionStatus.RTO, Disposition.RETURNED);
            case MARK_RESOLVED -> close(c, ExceptionStatus.RESOLVED, Disposition.RESOLVED);
            case MARK_MISSING -> {
                // Lost in the network: keep the case live (no resolvedAt) so it stays in the queue/rollups.
                c.setStatus(ExceptionStatus.IN_PROGRESS);
                c.setDisposition(Disposition.MISSING);
                caseRepo.save(c);
            }
        }
        logAction(c.getId(), action.name(), userId, role, notes);
    }

    /** Not @Transactional: each per-id {@code self.resolve()} opens its own (REQUIRED) transaction, so one
     *  bad case (closed / not found / not visible) is isolated and the rest still apply. */
    @Override
    public BatchResolveResponse batchResolve(BatchResolveRequest request, String cityScope,
                                             String userId, String role) {
        List<BatchResolveResponse.Item> results = new ArrayList<>();
        for (UUID caseId : request.caseIds()) {
            try {
                self.resolve(caseId, request.action(), cityScope, userId, role, request.notes());
                results.add(new BatchResolveResponse.Item(caseId, BatchResolveResponse.Status.OK, null));
            } catch (ResponseStatusException e) {
                BatchResolveResponse.Status status = switch (e.getStatusCode().value()) {
                    case 404 -> BatchResolveResponse.Status.NOT_FOUND;
                    case 409 -> BatchResolveResponse.Status.ALREADY_CLOSED;
                    default -> BatchResolveResponse.Status.ERROR;
                };
                results.add(new BatchResolveResponse.Item(caseId, status, e.getReason()));
            } catch (Exception e) {
                // Don't leak DB/broker/framework internals to the caller — log server-side, return generic.
                log.error("Batch resolve failed for case {}", caseId, e);
                results.add(new BatchResolveResponse.Item(caseId, BatchResolveResponse.Status.ERROR, "Resolution failed"));
            }
        }
        return new BatchResolveResponse(results);
    }

    /** Not @Transactional for the same reason as {@link #batchResolve} — each sibling resolves in its own tx. */
    @Override
    public BatchResolveResponse resolveByOrder(String orderRef, ResolveAction action, String cityScope,
                                               String userId, String role, String notes) {
        // Only the caller's visible siblings (own-city for a station manager; all for admin) are acted on,
        // so a cross-city sibling isn't silently RTO'd nor reported as NOT_FOUND noise.
        List<UUID> ids = caseRepo.findByOrderRefAndResolvedAtIsNull(orderRef).stream()
                .filter(c -> visible(c, cityScope))
                .map(ExceptionCase::getId)
                .toList();
        if (ids.isEmpty()) {
            return new BatchResolveResponse(List.of());
        }
        return self.batchResolve(new BatchResolveRequest(action, ids, notes), cityScope, userId, role);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────────────

    private void logAction(UUID caseId, String action, String actedBy, String actedByRole, String notes) {
        ExceptionAction a = new ExceptionAction();
        a.setCaseId(caseId);
        a.setAction(action);
        a.setActedBy(actedBy);
        a.setActedByRole(actedByRole);
        a.setNotes(notes);
        actionRepo.save(a);
    }

    private boolean visible(ExceptionCase c, String cityScope) {
        return cityScope == null
                || cityScope.equals(c.getOriginCity())
                || cityScope.equals(c.getDestCity());
    }
}
