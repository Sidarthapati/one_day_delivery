package com.oneday.exceptions.service.impl;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.common.kafka.enums.ExceptionsEventType;
import com.oneday.exceptions.config.ExceptionProperties;
import com.oneday.exceptions.domain.Disposition;
import com.oneday.exceptions.domain.ExceptionCase;
import com.oneday.exceptions.domain.ExceptionReason;
import com.oneday.exceptions.domain.ExceptionStatus;
import com.oneday.exceptions.domain.ExceptionType;
import com.oneday.exceptions.domain.ResolveAction;
import com.oneday.exceptions.events.ExceptionEventProducer;
import com.oneday.exceptions.repository.ExceptionActionRepository;
import com.oneday.exceptions.repository.ExceptionCaseRepository;
import com.oneday.orders.service.ShipmentLookupService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** M11 capture idempotency + attempt policy + resolve→producer wiring. */
class ExceptionCaseServiceImplTest {

    private final ExceptionCaseRepository caseRepo = mock(ExceptionCaseRepository.class);
    private final ExceptionActionRepository actionRepo = mock(ExceptionActionRepository.class);
    private final ShipmentLookupService lookup = mock(ShipmentLookupService.class);
    private final com.oneday.orders.service.ShipmentJourneyService journey =
            mock(com.oneday.orders.service.ShipmentJourneyService.class);
    private final com.oneday.common.port.CourierOnShipmentPort courier =
            mock(com.oneday.common.port.CourierOnShipmentPort.class);
    private final com.oneday.common.port.ShipmentContactPort contact =
            mock(com.oneday.common.port.ShipmentContactPort.class);
    private final ExceptionEventProducer producer = mock(ExceptionEventProducer.class);
    private final ExceptionProperties props = new ExceptionProperties(); // maxReattempts = 2

    private final ExceptionCaseServiceImpl svc =
            new ExceptionCaseServiceImpl(caseRepo, actionRepo, lookup, journey, courier, contact, producer, props);

    private final UUID shipmentId = UUID.randomUUID();

    /** resolve() defers the broker publish to afterCommit; run it inside an active synchronization and
     *  fire the commit callbacks so the publish actually happens (no real tx in a unit test). */
    private void resolveInTx(Runnable call) {
        TransactionSynchronizationManager.initSynchronization();
        try {
            call.run();
            TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private ExceptionCase saved() {
        ArgumentCaptor<ExceptionCase> cap = ArgumentCaptor.forClass(ExceptionCase.class);
        verify(caseRepo, org.mockito.Mockito.atLeastOnce()).save(cap.capture());
        return cap.getValue();
    }

    @Test
    void firstFailureOpensAReattemptableCase() {
        when(caseRepo.findFirstByShipmentIdAndResolvedAtIsNull(shipmentId)).thenReturn(Optional.empty());
        when(lookup.findByRef(any())).thenReturn(Optional.empty());
        when(caseRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        svc.captureDaFailure(shipmentId, "1DD-BLR-1", ExceptionType.DELIVERY_FAILED,
                ExceptionReason.CUSTOMER_UNAVAILABLE, false);

        ExceptionCase c = saved();
        assertThat(c.getAttemptNo()).isEqualTo(1);
        assertThat(c.getStatus()).isEqualTo(ExceptionStatus.OPEN);
        assertThat(c.getReasonCode()).isEqualTo(ExceptionReason.CUSTOMER_UNAVAILABLE);
        assertThat(c.getDisposition()).isEqualTo(Disposition.REATTEMPTABLE);
    }

    @Test
    void attemptCapFlipsToUndeliverable() {
        ExceptionCase live = new ExceptionCase();
        live.setShipmentId(shipmentId);
        live.setType(ExceptionType.DELIVERY_FAILED);
        live.setAttemptNo(2); // already two attempts; the third crosses maxReattempts=2
        when(caseRepo.findFirstByShipmentIdAndResolvedAtIsNull(shipmentId)).thenReturn(Optional.of(live));
        when(caseRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        svc.captureDaFailure(shipmentId, "1DD-BLR-1", ExceptionType.DELIVERY_FAILED, ExceptionReason.UNKNOWN, false);

        assertThat(live.getAttemptNo()).isEqualTo(3);
        assertThat(live.getStatus()).isEqualTo(ExceptionStatus.OPEN); // re-opened for action
        assertThat(live.getDisposition()).isEqualTo(Disposition.UNDELIVERABLE);
    }

    @Test
    void secondAttemptStaysReattemptable() {
        ExceptionCase live = new ExceptionCase();
        live.setType(ExceptionType.DELIVERY_FAILED);
        live.setAttemptNo(1);
        when(caseRepo.findFirstByShipmentIdAndResolvedAtIsNull(shipmentId)).thenReturn(Optional.of(live));
        when(caseRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        svc.captureDaFailure(shipmentId, "1DD-BLR-1", ExceptionType.DELIVERY_FAILED, ExceptionReason.UNKNOWN, false);

        assertThat(live.getAttemptNo()).isEqualTo(2);
        assertThat(live.getDisposition()).isEqualTo(Disposition.REATTEMPTABLE);
    }

    @Test
    void resolveRescheduleDeliveryPublishesTheDrivingEvent() {
        UUID caseId = UUID.randomUUID();
        ExceptionCase c = new ExceptionCase();
        c.setShipmentId(shipmentId);
        when(caseRepo.findById(caseId)).thenReturn(Optional.of(c));
        when(caseRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        resolveInTx(() -> svc.resolve(caseId, ResolveAction.RESCHEDULE_DELIVERY, null, "u1", "STATION_MANAGER", "retry tonight"));

        verify(producer).publish(shipmentId, ExceptionsEventType.DELIVERY_RESCHEDULED);
        assertThat(c.getStatus()).isEqualTo(ExceptionStatus.RESCHEDULED);
        assertThat(c.getResolution()).isEqualTo(ResolveAction.RESCHEDULE_DELIVERY);
    }

    @Test
    void resolveInitiateRtoMarksReturnedAndPublishes() {
        UUID caseId = UUID.randomUUID();
        ExceptionCase c = new ExceptionCase();
        c.setShipmentId(shipmentId);
        when(caseRepo.findById(caseId)).thenReturn(Optional.of(c));
        when(caseRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        resolveInTx(() -> svc.resolve(caseId, ResolveAction.INITIATE_RTO, null, "u1", "STATION_MANAGER", null));

        verify(producer).publish(shipmentId, ExceptionsEventType.RTO_INITIATED);
        assertThat(c.getStatus()).isEqualTo(ExceptionStatus.RTO);
        assertThat(c.getDisposition()).isEqualTo(Disposition.RETURNED);
    }

    @Test
    void markResolvedClosesWithoutPublishing() {
        UUID caseId = UUID.randomUUID();
        ExceptionCase c = new ExceptionCase();
        c.setShipmentId(shipmentId);
        when(caseRepo.findById(caseId)).thenReturn(Optional.of(c));
        when(caseRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        svc.resolve(caseId, ResolveAction.MARK_RESOLVED, null, "u1", "STATION_MANAGER", "sorted offline");

        verify(producer, never()).publish(any(), any());
        assertThat(c.getResolvedAt()).isNotNull();
        assertThat(c.getStatus()).isEqualTo(ExceptionStatus.RESOLVED);
    }

    @Test
    void daFailureDuringRtoDoesNotReopenTheCase() {
        ExceptionCase live = new ExceptionCase();
        live.setShipmentId(shipmentId);
        live.setStatus(ExceptionStatus.RTO);
        live.setDisposition(Disposition.RETURNED);
        live.setAttemptNo(3);
        when(caseRepo.findFirstByShipmentIdAndResolvedAtIsNull(shipmentId)).thenReturn(Optional.of(live));

        // Return-leg drop fails and re-emits a DELIVERY_FAILED — must not reset the RTO case to OPEN.
        svc.captureDaFailure(shipmentId, "1DD-BLR-1", ExceptionType.DELIVERY_FAILED, ExceptionReason.UNKNOWN, false);

        assertThat(live.getStatus()).isEqualTo(ExceptionStatus.RTO);
        assertThat(live.getDisposition()).isEqualTo(Disposition.RETURNED);
        assertThat(live.getAttemptNo()).isEqualTo(3);
        verify(caseRepo, never()).save(any()); // trail-only; no mutation of the case
    }

    @Test
    void cancellationClosesTheLiveCase() {
        ExceptionCase live = new ExceptionCase();
        live.setStatus(ExceptionStatus.OPEN);
        when(caseRepo.findFirstByShipmentIdAndResolvedAtIsNull(shipmentId)).thenReturn(Optional.of(live));
        when(caseRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        svc.onShipmentStateChanged(shipmentId, ShipmentState.CANCELLED);

        assertThat(live.getResolvedAt()).isNotNull();
        assertThat(live.getStatus()).isEqualTo(ExceptionStatus.CANCELLED);
    }

    @Test
    void successfulTerminalDeliveryClosesTheLiveCase() {
        ExceptionCase live = new ExceptionCase();
        when(caseRepo.findFirstByShipmentIdAndResolvedAtIsNull(shipmentId)).thenReturn(Optional.of(live));
        when(caseRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        svc.onShipmentStateChanged(shipmentId, ShipmentState.DROPPED);

        assertThat(live.getResolvedAt()).isNotNull();
        assertThat(live.getDisposition()).isEqualTo(Disposition.RESOLVED);
    }
}
