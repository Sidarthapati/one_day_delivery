package com.oneday.routing.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oneday.routing.domain.DiscrepancyType;
import com.oneday.routing.domain.HandoffDirection;
import com.oneday.routing.domain.HandoffReconciliation;
import com.oneday.routing.domain.ManifestItemStatus;
import com.oneday.routing.domain.RoutePlan;
import com.oneday.routing.domain.VanManifest;
import com.oneday.routing.domain.VanManifestItem;
import com.oneday.routing.events.CronEventProducer;
import com.oneday.routing.repository.HandoffReconciliationRepository;
import com.oneday.routing.repository.RoutePlanRepository;
import com.oneday.routing.repository.VanManifestItemRepository;
import com.oneday.routing.repository.VanManifestRepository;
import com.oneday.routing.service.model.StopReconciliation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HandoffServiceImplTest {

    @Mock VanManifestRepository manifestRepository;
    @Mock VanManifestItemRepository itemRepository;
    @Mock HandoffReconciliationRepository reconciliationRepository;
    @Mock RoutePlanRepository planRepository;
    @Mock CronEventProducer cronEventProducer;

    private static final UUID CITY = UUID.randomUUID();
    private static final UUID PLAN = UUID.randomUUID();
    private static final UUID VAN = UUID.randomUUID();
    private static final UUID DA = UUID.randomUUID();
    private static final UUID MANIFEST = UUID.randomUUID();
    private static final int STOP = 1;
    private static final LocalDate DATE = LocalDate.of(2026, 6, 18);

    private final List<VanManifestItem> itemStore = new ArrayList<>();
    private final List<HandoffReconciliation> recons = new ArrayList<>();

    private HandoffServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new HandoffServiceImpl(manifestRepository, itemRepository, reconciliationRepository,
                planRepository, cronEventProducer, new ObjectMapper());
        when(manifestRepository.findByVanIdAndLoopIndexAndValidDate(VAN, 0, DATE))
                .thenReturn(Optional.of(VanManifest.builder().id(MANIFEST).vanId(VAN).loopIndex(0)
                        .routePlanId(PLAN).validDate(DATE).build()));
        when(planRepository.findById(PLAN))
                .thenReturn(Optional.of(RoutePlan.builder().id(PLAN).cityId(CITY).build()));
        when(itemRepository.findByManifestIdAndStopSeq(eq(MANIFEST), eq(STOP))).thenAnswer(inv -> itemStore);
        when(itemRepository.save(any(VanManifestItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reconciliationRepository.save(any(HandoffReconciliation.class))).thenAnswer(inv -> {
            recons.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
    }

    @Test
    void cleanStop_emitsCompleted_noDiscrepancy() {
        UUID d = deliver(ManifestItemStatus.HANDED_OFF);

        StopReconciliation r = service.reconcileStop(VAN, 0, DATE, STOP, DA, Set.of(d), Set.of(), Set.of());

        assertThat(r.clean()).isTrue();
        verify(cronEventProducer).emitHandoffCompleted(MANIFEST, VAN, CITY, STOP, DA);
        verify(cronEventProducer, never()).emitHandoffDiscrepancy(any(), any(), any(), eq(STOP), any(), any(), any());
    }

    @Test
    void missingDelivery_emitsDiscrepancy_marksException() {
        UUID handed = deliver(ManifestItemStatus.HANDED_OFF);
        UUID missing = deliver(ManifestItemStatus.LOADED);

        // Only the handed parcel was scanned out; the other is MISSING.
        StopReconciliation r = service.reconcileStop(VAN, 0, DATE, STOP, DA, Set.of(handed), Set.of(), Set.of());

        assertThat(r.clean()).isFalse();
        assertThat(r.discrepancies()).anyMatch(d -> d.type() == DiscrepancyType.MISSING && d.parcelIds().contains(missing));
        verify(cronEventProducer).emitHandoffDiscrepancy(eq(MANIFEST), eq(VAN), eq(CITY), eq(STOP), eq(DA),
                eq(DiscrepancyType.MISSING), eq(List.of(missing)));
        assertThat(item(missing).getStatus()).isEqualTo(ManifestItemStatus.EXCEPTION);
        verify(cronEventProducer, never()).emitHandoffCompleted(any(), any(), any(), eq(STOP), any());
    }

    @Test
    void extraScan_notOnManifest_isFlaggedExtra() {
        UUID handed = deliver(ManifestItemStatus.HANDED_OFF);
        UUID stray = UUID.randomUUID(); // scanned but never bound to this van

        StopReconciliation r = service.reconcileStop(VAN, 0, DATE, STOP, DA, Set.of(handed, stray), Set.of(), Set.of());

        assertThat(r.discrepancies()).anyMatch(d -> d.type() == DiscrepancyType.EXTRA && d.parcelIds().contains(stray));
        verify(cronEventProducer).emitHandoffDiscrepancy(eq(MANIFEST), eq(VAN), eq(CITY), eq(STOP), eq(DA),
                eq(DiscrepancyType.EXTRA), eq(List.of(stray)));
    }

    @Test
    void rejectedDelivery_isFlaggedRejected_andException() {
        UUID rejected = deliver(ManifestItemStatus.LOADED);

        StopReconciliation r = service.reconcileStop(VAN, 0, DATE, STOP, DA, Set.of(), Set.of(), Set.of(rejected));

        assertThat(r.discrepancies()).anyMatch(d -> d.type() == DiscrepancyType.REJECTED && d.parcelIds().contains(rejected));
        assertThat(item(rejected).getStatus()).isEqualTo(ManifestItemStatus.EXCEPTION);
    }

    @Test
    void noShow_emptyScans_allMissing() {
        UUID a = deliver(ManifestItemStatus.LOADED);
        UUID b = deliver(ManifestItemStatus.LOADED);

        StopReconciliation r = service.reconcileStop(VAN, 0, DATE, STOP, DA, Set.of(), Set.of(), Set.of());

        assertThat(r.clean()).isFalse();
        assertThat(r.discrepancies()).anyMatch(d -> d.type() == DiscrepancyType.MISSING
                && d.parcelIds().containsAll(List.of(a, b)));
        verify(cronEventProducer, times(1)).emitHandoffDiscrepancy(any(), any(), any(), eq(STOP), any(),
                eq(DiscrepancyType.MISSING), any());
    }

    private UUID deliver(ManifestItemStatus status) {
        UUID parcel = UUID.randomUUID();
        itemStore.add(VanManifestItem.builder().id(UUID.randomUUID()).manifestId(MANIFEST).parcelId(parcel)
                .direction(HandoffDirection.DELIVER).stopSeq(STOP).counterpartyDaId(DA).status(status).build());
        return parcel;
    }

    private VanManifestItem item(UUID parcel) {
        return itemStore.stream().filter(i -> i.getParcelId().equals(parcel)).findFirst().orElseThrow();
    }
}
