package com.oneday.routing.service.impl;

import com.oneday.routing.config.ClockConfig;
import com.oneday.routing.domain.HandoffDirection;
import com.oneday.routing.domain.ManifestItemStatus;
import com.oneday.routing.domain.ManifestStatus;
import com.oneday.routing.domain.VanManifest;
import com.oneday.routing.domain.VanManifestItem;
import com.oneday.routing.events.CronEventProducer;
import com.oneday.routing.repository.VanManifestItemRepository;
import com.oneday.routing.repository.VanManifestRepository;
import com.oneday.routing.service.VanManifestService;
import com.oneday.routing.service.model.BindOutcome;
import com.oneday.routing.service.model.RecoverySummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RecoveryServiceImplTest {

    @Mock VanManifestRepository manifestRepository;
    @Mock VanManifestItemRepository itemRepository;
    @Mock VanManifestService manifestService;
    @Mock CronEventProducer cronEventProducer;

    private static final UUID CITY = UUID.randomUUID();
    private static final UUID PLAN = UUID.randomUUID();
    private static final UUID BROKEN = UUID.randomUUID();
    private static final UUID RECOVERY = UUID.randomUUID();
    private static final UUID DA = UUID.randomUUID();
    private static final LocalDate DATE = LocalDate.of(2026, 6, 18);

    private final Clock clock = Clock.fixed(Instant.parse("2026-06-18T10:00:00Z"), ClockConfig.IST);
    private final List<VanManifestItem> itemStore = new ArrayList<>();

    private RecoveryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RecoveryServiceImpl(manifestRepository, itemRepository, manifestService, cronEventProducer, clock);
        when(itemRepository.save(any(VanManifestItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(manifestRepository.saveAndFlush(any(VanManifest.class))).thenAnswer(inv -> {
            VanManifest m = inv.getArgument(0);
            m.setId(UUID.randomUUID());
            return m;
        });
    }

    @Test
    void recoverVan_emitsBreakdown_andReassignsOpenItems() {
        UUID brokenManifest = UUID.randomUUID();
        when(manifestRepository.findByVanIdAndValidDate(BROKEN, DATE)).thenReturn(List.of(
                VanManifest.builder().id(brokenManifest).vanId(BROKEN).loopIndex(2).routePlanId(PLAN)
                        .validDate(DATE).status(ManifestStatus.IN_PROGRESS).build()));
        when(manifestRepository.findByVanIdAndLoopIndexAndValidDate(RECOVERY, 2, DATE)).thenReturn(Optional.empty());
        VanManifestItem open = item(brokenManifest, ManifestItemStatus.LOADED);
        VanManifestItem done = item(brokenManifest, ManifestItemStatus.HANDED_OFF); // terminal, not moved
        when(itemRepository.findByManifestId(brokenManifest)).thenReturn(itemStore);

        RecoverySummary summary = service.recoverVan(BROKEN, RECOVERY, CITY, DATE, 12.9, 77.6);

        verify(cronEventProducer).emitVanBreakdown(eq(BROKEN), eq(CITY), eq(PLAN), eq(12.9), eq(77.6), any());
        assertThat(summary.itemsReassigned()).isEqualTo(1);
        assertThat(open.getManifestId()).isNotEqualTo(brokenManifest); // moved to recovery manifest
        assertThat(done.getManifestId()).isEqualTo(brokenManifest);    // terminal left in place
    }

    @Test
    void carryNoShow_rebindsUndeliveredDeliveries() {
        UUID manifest = UUID.randomUUID();
        when(manifestRepository.findByVanIdAndLoopIndexAndValidDate(BROKEN, 1, DATE)).thenReturn(Optional.of(
                VanManifest.builder().id(manifest).vanId(BROKEN).loopIndex(1).validDate(DATE).build()));
        VanManifestItem loaded = item(manifest, ManifestItemStatus.LOADED);
        item(manifest, ManifestItemStatus.HANDED_OFF); // already delivered — not carried
        when(itemRepository.findByManifestIdAndStopSeq(manifest, 3)).thenReturn(itemStore);
        when(manifestService.rebindDelivery(any())).thenReturn(BindOutcome.bound(loaded.getParcelId(), 2, BROKEN, UUID.randomUUID(), List.of()));

        int carried = service.carryNoShow(BROKEN, 1, DATE, 3, DA);

        assertThat(carried).isEqualTo(1);
        verify(manifestService, times(1)).rebindDelivery(loaded.getParcelId());
    }

    private VanManifestItem item(UUID manifestId, ManifestItemStatus status) {
        VanManifestItem i = VanManifestItem.builder().id(UUID.randomUUID()).manifestId(manifestId)
                .parcelId(UUID.randomUUID()).direction(HandoffDirection.DELIVER).stopSeq(3)
                .counterpartyDaId(DA).status(status).build();
        itemStore.add(i);
        return i;
    }
}
