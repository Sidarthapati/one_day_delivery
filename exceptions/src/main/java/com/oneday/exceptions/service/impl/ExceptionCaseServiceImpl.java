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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ExceptionCaseServiceImpl implements ExceptionCaseService {

    private static final int MAX_PAGE_SIZE = 100;

    private final ExceptionCaseRepository caseRepo;
    private final ExceptionActionRepository actionRepo;
    private final ShipmentLookupService shipmentLookup;
    private final ShipmentJourneyService journeyService;
    private final CourierOnShipmentPort courierPort;
    private final ShipmentContactPort contactPort;
    private final ExceptionEventProducer producer;
    private final ExceptionProperties props;

    public ExceptionCaseServiceImpl(ExceptionCaseRepository caseRepo, ExceptionActionRepository actionRepo,
                                    ShipmentLookupService shipmentLookup, ShipmentJourneyService journeyService,
                                    CourierOnShipmentPort courierPort, ShipmentContactPort contactPort,
                                    ExceptionEventProducer producer, ExceptionProperties props) {
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
            case RTO_INITIATED, RTO_IN_TRANSIT -> {
                c.setStatus(ExceptionStatus.RTO);
                c.setDisposition(Disposition.RETURNED);
                caseRepo.save(c);
            }
            case RTO_COMPLETED -> close(c, ExceptionStatus.RTO, Disposition.RETURNED);
            // A successful terminal delivery clears any lingering case (e.g. a reschedule that landed).
            case DROPPED, HUB_COLLECTED -> close(c, ExceptionStatus.RESOLVED, Disposition.RESOLVED);
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
        long open = 0, reattemptable = 0, undeliverable = 0, returned = 0;
        for (Object[] row : caseRepo.countOpenByDisposition(cityScope)) {
            Disposition d = (Disposition) row[0];
            long n = (Long) row[1];
            open += n;
            switch (d) {
                case REATTEMPTABLE -> reattemptable += n;
                case UNDELIVERABLE -> undeliverable += n;
                case RETURNED -> returned += n;
                case RESOLVED -> { /* resolved cases aren't in the live set */ }
            }
        }
        return new ExceptionSummaryResponse(open, reattemptable, undeliverable, returned);
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
        return new ExceptionCaseDetail(ExceptionCaseSummary.from(c), actions, journey, handler, receiver);
    }

    // ── Resolve ───────────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void resolve(UUID caseId, ResolveAction action, String cityScope,
                        String userId, String role, String notes) {
        ExceptionCase c = caseRepo.findById(caseId)
                .filter(x -> visible(x, cityScope))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found"));
        if (c.getResolvedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Case is already closed");
        }

        // Drive M4 (and, downstream, M10) by publishing the matching event — the orders consumer transitions.
        if (action.event() != null) {
            producer.publish(c.getShipmentId(), action.event());
        }

        c.setResolution(action);
        c.setAssignedTo(userId);
        c.setAssignedRole(role);
        if (notes != null && !notes.isBlank()) {
            c.setNotes(notes);
        }
        switch (action) {
            case RESCHEDULE_PICKUP, RESCHEDULE_DELIVERY -> {
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
        }
        logAction(c.getId(), action.name(), userId, role, notes);
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
