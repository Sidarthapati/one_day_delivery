package com.oneday.sla.service;

import com.oneday.common.domain.enums.DeliveryType;
import com.oneday.common.domain.enums.SlaLegType;
import com.oneday.common.kafka.events.ShipmentCreatedEvent;
import com.oneday.sla.config.SlaProperties;
import com.oneday.sla.domain.SlaLeg;
import com.oneday.sla.domain.SlaShipment;
import com.oneday.sla.repository.SlaLegRepository;
import com.oneday.sla.repository.SlaShipmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** M10 pickup-anchored clock: no SLA target at booking; targets + first-mile leg start at pickup. */
class SlaLifecycleServiceClockTest {

    private SlaShipmentRepository shipmentRepo;
    private SlaLegRepository legRepo;
    private SlaLegCatalog catalog;
    private SlaEngine engine;
    private SlaLifecycleService service;

    private final UUID shipmentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        shipmentRepo = mock(SlaShipmentRepository.class);
        legRepo = mock(SlaLegRepository.class);
        catalog = mock(SlaLegCatalog.class);
        engine = mock(SlaEngine.class);
        service = new SlaLifecycleService(shipmentRepo, legRepo, catalog, new SlaProperties(), engine,
                mock(EscalationService.class));
    }

    @Test
    void onCreated_doesNotStartTheClock() {
        when(shipmentRepo.findByShipmentId(shipmentId)).thenReturn(Optional.empty());
        when(catalog.plan(any())).thenReturn(List.of(SlaLegType.FIRST_MILE));
        when(catalog.budgetMinutes(SlaLegType.FIRST_MILE)).thenReturn(180);

        ShipmentCreatedEvent e = new ShipmentCreatedEvent();
        e.setShipmentId(shipmentId);
        e.setOccurredAt(Instant.now());
        e.setDeliveryType(DeliveryType.INTERCITY);
        service.onCreated(e);

        ArgumentCaptor<SlaShipment> cap = ArgumentCaptor.forClass(SlaShipment.class);
        verify(shipmentRepo).save(cap.capture());
        assertThat(cap.getValue().getInternalTargetAt()).isNull();
        assertThat(cap.getValue().getPublicPromiseAt()).isNull();

        ArgumentCaptor<SlaLeg> legCap = ArgumentCaptor.forClass(SlaLeg.class);
        verify(legRepo).save(legCap.capture());
        assertThat(legCap.getValue().getStartedAt()).isNull();   // first-mile not running pre-pickup
    }

    @Test
    void startClocks_anchorsTargetsAndFirstLegAtPickup() {
        Instant pickup = Instant.now();
        SlaShipment ss = new SlaShipment();
        ss.setShipmentId(shipmentId);   // internalTargetAt null → clock not yet started
        when(shipmentRepo.findByShipmentId(shipmentId)).thenReturn(Optional.of(ss));
        SlaLeg leg0 = new SlaLeg();
        leg0.setSeq(0);
        leg0.setBudgetMinutes(180);
        when(legRepo.findByShipmentIdOrderBySeqAsc(shipmentId)).thenReturn(List.of(leg0));

        service.startClocks(shipmentId, pickup);

        assertThat(ss.getInternalTargetAt())
                .isCloseTo(pickup.plus(Duration.ofHours(16)), within(1, ChronoUnit.SECONDS));
        assertThat(ss.getPublicPromiseAt())
                .isCloseTo(pickup.plus(Duration.ofHours(24)), within(1, ChronoUnit.SECONDS));
        assertThat(leg0.getStartedAt()).isEqualTo(pickup);
        assertThat(leg0.getDeadlineAt())
                .isCloseTo(pickup.plus(Duration.ofMinutes(180)), within(1, ChronoUnit.SECONDS));
    }

    @Test
    void startClocks_isIdempotentOnceStarted() {
        SlaShipment ss = new SlaShipment();
        ss.setShipmentId(shipmentId);
        Instant already = Instant.now().minus(1, ChronoUnit.HOURS);
        ss.setInternalTargetAt(already);   // clock already running
        when(shipmentRepo.findByShipmentId(shipmentId)).thenReturn(Optional.of(ss));

        service.startClocks(shipmentId, Instant.now());

        assertThat(ss.getInternalTargetAt()).isEqualTo(already);   // untouched
    }
}
