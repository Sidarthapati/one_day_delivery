package com.oneday.routing.service.impl;

import com.oneday.routing.domain.HandoffDirection;
import com.oneday.routing.domain.ManifestItemStatus;
import com.oneday.routing.domain.ManifestStatus;
import com.oneday.routing.domain.VanManifest;
import com.oneday.routing.domain.VanManifestItem;
import com.oneday.routing.repository.VanManifestItemRepository;
import com.oneday.routing.repository.VanManifestRepository;
import com.oneday.routing.service.model.CustodyResult;
import com.oneday.routing.service.model.VanCustodyCommand;
import com.oneday.routing.service.port.ScanLedgerPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CustodyServiceImplTest {

    @Mock VanManifestItemRepository itemRepository;
    @Mock VanManifestRepository manifestRepository;
    @Mock ScanLedgerPort scanLedgerPort;

    private static final UUID VAN = UUID.randomUUID();
    private static final UUID DRIVER = UUID.randomUUID();
    private static final UUID DA = UUID.randomUUID();

    private final List<VanManifestItem> itemStore = new ArrayList<>();
    private final Map<UUID, VanManifest> manifestStore = new HashMap<>();

    private CustodyServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CustodyServiceImpl(itemRepository, manifestRepository, scanLedgerPort);
        when(itemRepository.findByParcelId(any())).thenAnswer(inv -> itemStore.stream()
                .filter(i -> i.getParcelId().equals(inv.getArgument(0))).toList());
        when(itemRepository.findByManifestId(any())).thenAnswer(inv -> itemStore.stream()
                .filter(i -> i.getManifestId().equals(inv.getArgument(0))).toList());
        when(itemRepository.save(any(VanManifestItem.class))).thenAnswer(inv -> inv.getArgument(0));
        when(manifestRepository.findById(any())).thenAnswer(inv -> Optional.ofNullable(manifestStore.get(inv.getArgument(0))));
        when(manifestRepository.save(any(VanManifest.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void fullDeliverChain_loadThenDeliver_advancesAndSealsManifest() {
        UUID manifestId = newManifest(ManifestStatus.BUILDING);
        UUID parcel = newItem(manifestId, HandoffDirection.DELIVER, ManifestItemStatus.PLANNED);

        CustodyResult load = service.record(scan(parcel, ScanLedgerPort.VanScanType.VAN_LOAD, DA));
        assertThat(load.status()).isEqualTo(CustodyResult.Status.RECORDED);
        assertThat(item(parcel).getStatus()).isEqualTo(ManifestItemStatus.LOADED);
        assertThat(manifestStore.get(manifestId).getStatus()).isEqualTo(ManifestStatus.LOADED); // BUILDING → LOADED

        CustodyResult deliver = service.record(scan(parcel, ScanLedgerPort.VanScanType.VAN_TO_DA, DA));
        assertThat(deliver.status()).isEqualTo(CustodyResult.Status.RECORDED);
        assertThat(item(parcel).getStatus()).isEqualTo(ManifestItemStatus.HANDED_OFF);

        verify(scanLedgerPort, times(2)).recordVanScan(any()); // every scan hits the ledger (M8)
    }

    @Test
    void collectChain_collectThenUnload_reconcilesManifest() {
        UUID manifestId = newManifest(ManifestStatus.IN_PROGRESS);
        UUID parcel = newItem(manifestId, HandoffDirection.COLLECT, ManifestItemStatus.PLANNED);

        service.record(scan(parcel, ScanLedgerPort.VanScanType.DA_TO_VAN, DA));
        assertThat(item(parcel).getStatus()).isEqualTo(ManifestItemStatus.ONBOARD);

        service.record(scan(parcel, ScanLedgerPort.VanScanType.VAN_UNLOAD, null));
        assertThat(item(parcel).getStatus()).isEqualTo(ManifestItemStatus.RECONCILED);
        assertThat(manifestStore.get(manifestId).getStatus()).isEqualTo(ManifestStatus.RECONCILED); // all items terminal
    }

    @Test
    void outOfOrderScan_isRejected_C12() {
        UUID manifestId = newManifest(ManifestStatus.BUILDING);
        UUID parcel = newItem(manifestId, HandoffDirection.DELIVER, ManifestItemStatus.PLANNED);

        // VAN_TO_DA before VAN_LOAD — illegal predecessor (must be LOADED).
        CustodyResult r = service.record(scan(parcel, ScanLedgerPort.VanScanType.VAN_TO_DA, DA));
        assertThat(r.status()).isEqualTo(CustodyResult.Status.ILLEGAL_TRANSITION);
        assertThat(item(parcel).getStatus()).isEqualTo(ManifestItemStatus.PLANNED); // unchanged
        verify(scanLedgerPort, times(1)).recordVanScan(any()); // scan still recorded
    }

    @Test
    void replayedScan_isIdempotent() {
        UUID manifestId = newManifest(ManifestStatus.BUILDING);
        UUID parcel = newItem(manifestId, HandoffDirection.DELIVER, ManifestItemStatus.PLANNED);

        service.record(scan(parcel, ScanLedgerPort.VanScanType.VAN_LOAD, DA));
        CustodyResult replay = service.record(scan(parcel, ScanLedgerPort.VanScanType.VAN_LOAD, DA));
        assertThat(replay.status()).isEqualTo(CustodyResult.Status.IDEMPOTENT);
        assertThat(item(parcel).getStatus()).isEqualTo(ManifestItemStatus.LOADED);
    }

    @Test
    void scanForParcelNotOnManifest_isUnknown() {
        CustodyResult r = service.record(scan(UUID.randomUUID(), ScanLedgerPort.VanScanType.VAN_TO_DA, DA));
        assertThat(r.status()).isEqualTo(CustodyResult.Status.UNKNOWN_PARCEL);
        verify(scanLedgerPort, times(1)).recordVanScan(any()); // physical scan still logged
    }

    private VanCustodyCommand scan(UUID parcel, ScanLedgerPort.VanScanType type, UUID da) {
        return new VanCustodyCommand(parcel, type, VAN, DRIVER, da, Instant.now());
    }

    private UUID newManifest(ManifestStatus status) {
        UUID id = UUID.randomUUID();
        manifestStore.put(id, VanManifest.builder().id(id).vanId(VAN).loopIndex(0).status(status).build());
        return id;
    }

    private UUID newItem(UUID manifestId, HandoffDirection dir, ManifestItemStatus status) {
        UUID parcel = UUID.randomUUID();
        itemStore.add(VanManifestItem.builder().id(UUID.randomUUID()).manifestId(manifestId).parcelId(parcel)
                .direction(dir).counterpartyDaId(DA).status(status).build());
        return parcel;
    }

    private VanManifestItem item(UUID parcel) {
        return itemStore.stream().filter(i -> i.getParcelId().equals(parcel)).findFirst().orElseThrow();
    }
}
