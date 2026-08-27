package com.oneday.assets.service.impl;

import com.oneday.assets.config.AssetProperties;
import com.oneday.assets.domain.Asset;
import com.oneday.assets.domain.AssetCategory;
import com.oneday.assets.domain.AssetCondition;
import com.oneday.assets.domain.AssetCustodyEvent;
import com.oneday.assets.domain.AssetEventType;
import com.oneday.assets.domain.AssetStatus;
import com.oneday.assets.domain.HolderType;
import com.oneday.assets.dto.AssetView;
import com.oneday.assets.dto.EvidenceUpload;
import com.oneday.assets.dto.RegisterAssetRequest;
import com.oneday.assets.dto.SelectVanRequest;
import com.oneday.assets.events.AssetCustodyChanged;
import com.oneday.assets.repository.AssetCustodyEventRepository;
import com.oneday.assets.repository.AssetRepository;
import com.oneday.common.port.DaDirectoryPort;
import com.oneday.common.port.ObjectStoragePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AssetServiceImplTest {

    @Mock AssetRepository assets;
    @Mock AssetCustodyEventRepository custody;
    @Mock ObjectStoragePort storage;
    @Mock DaDirectoryPort daDirectory;
    @Mock ApplicationEventPublisher appEvents;

    private AssetServiceImpl service;

    private final UUID cityId = UUID.randomUUID();
    private final UUID daId = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        AssetProperties props = new AssetProperties();
        service = new AssetServiceImpl(assets, custody, storage, props, daDirectory, appEvents);
        when(assets.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(daDirectory.contactsFor(any())).thenReturn(Map.of(daId, new DaDirectoryPort.DaContact("Ravi", "9")));
    }

    private Asset asset(AssetStatus status, HolderType holderType, UUID holderId) {
        Asset a = new Asset();
        a.setAssetTag("DEL-VAN-001");
        a.setCategory(AssetCategory.VEHICLE);
        a.setAssetType("VAN");
        a.setName("Tata Ace");
        a.setCityId(cityId);
        a.setStatus(status);
        a.setCurrentHolderType(holderType);
        a.setCurrentHolderId(holderId);
        a.setAckPending(status == AssetStatus.ASSIGNED);
        return a;
    }

    @Test
    void register_createsInStockAtStation_andRecordsRegistered() {
        when(assets.existsByAssetTag("DEL-SCN-1")).thenReturn(false);
        RegisterAssetRequest req = new RegisterAssetRequest("DEL-SCN-1", AssetCategory.SCANNER,
                "HANDHELD_SCANNER", "Zebra", null, null, null, null, cityId, null, null);

        AssetView v = service.register(req, cityId, actor);

        assertThat(v.status()).isEqualTo("IN_STOCK");
        assertThat(v.currentHolderType()).isEqualTo("STATION");
        ArgumentCaptor<AssetCustodyEvent> cap = ArgumentCaptor.forClass(AssetCustodyEvent.class);
        verify(custody).save(cap.capture());
        assertThat(cap.getValue().getEventType()).isEqualTo(AssetEventType.REGISTERED);
        verify(appEvents).publishEvent(any(AssetCustodyChanged.class));
    }

    @Test
    void register_duplicateTag_conflicts() {
        when(assets.existsByAssetTag("DEL-VAN-001")).thenReturn(true);
        RegisterAssetRequest req = new RegisterAssetRequest("DEL-VAN-001", AssetCategory.VEHICLE,
                "VAN", "Ace", null, null, null, null, cityId, null, null);
        assertThatThrownBy(() -> service.register(req, cityId, actor))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
    }

    @Test
    void register_rejectsPhotoKeyOutsideCityPrefix() {
        when(assets.existsByAssetTag(any())).thenReturn(false);
        RegisterAssetRequest req = new RegisterAssetRequest("DEL-VAN-002", AssetCategory.VEHICLE, "VAN",
                "Ace", null, null, null, null, cityId, null, List.of("asset-photos/2026/08/27/OTHER/x.jpg"));
        assertThatThrownBy(() -> service.register(req, cityId, actor))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
    }

    @Test
    void issue_fromInStock_assignsToDaAndFlagsAck() {
        UUID id = UUID.randomUUID();
        when(assets.findByIdForUpdate(id)).thenReturn(Optional.of(asset(AssetStatus.IN_STOCK, HolderType.STATION, null)));

        AssetView v = service.issue(id, daId, "shift start", cityId, actor);

        assertThat(v.status()).isEqualTo("ASSIGNED");
        assertThat(v.currentHolderType()).isEqualTo("USER");
        assertThat(v.currentHolderId()).isEqualTo(daId);
        assertThat(v.currentHolderName()).isEqualTo("Ravi");
        assertThat(v.ackPending()).isTrue();
        ArgumentCaptor<AssetCustodyEvent> cap = ArgumentCaptor.forClass(AssetCustodyEvent.class);
        verify(custody).save(cap.capture());
        assertThat(cap.getValue().getEventType()).isEqualTo(AssetEventType.ISSUED);
    }

    @Test
    void issue_whenAlreadyAssigned_conflicts() {
        UUID id = UUID.randomUUID();
        when(assets.findByIdForUpdate(id)).thenReturn(Optional.of(asset(AssetStatus.ASSIGNED, HolderType.USER, daId)));
        assertThatThrownBy(() -> service.issue(id, UUID.randomUUID(), null, cityId, actor))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
        verify(custody, never()).save(any());
    }

    @Test
    void issue_outsideManagerCity_notFound() {
        UUID id = UUID.randomUUID();
        when(assets.findByIdForUpdate(id)).thenReturn(Optional.of(asset(AssetStatus.IN_STOCK, HolderType.STATION, null)));
        UUID otherCity = UUID.randomUUID();
        assertThatThrownBy(() -> service.issue(id, daId, null, otherCity, actor))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void returnToStation_fromAssigned_backToStock() {
        UUID id = UUID.randomUUID();
        when(assets.findByIdForUpdate(id)).thenReturn(Optional.of(asset(AssetStatus.ASSIGNED, HolderType.USER, daId)));

        AssetView v = service.returnToStation(id, AssetCondition.FAIR, "shift end", cityId, actor);

        assertThat(v.status()).isEqualTo("IN_STOCK");
        assertThat(v.currentHolderType()).isEqualTo("STATION");
        assertThat(v.currentHolderId()).isNull();
        assertThat(v.condition()).isEqualTo("FAIR");
    }

    @Test
    void returnToStation_whenInStock_conflicts() {
        UUID id = UUID.randomUUID();
        when(assets.findByIdForUpdate(id)).thenReturn(Optional.of(asset(AssetStatus.IN_STOCK, HolderType.STATION, null)));
        assertThatThrownBy(() -> service.returnToStation(id, null, null, cityId, actor))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
    }

    @Test
    void transfer_movesHolderToNewDa() {
        UUID id = UUID.randomUUID();
        UUID otherDa = UUID.randomUUID();
        when(assets.findByIdForUpdate(id)).thenReturn(Optional.of(asset(AssetStatus.ASSIGNED, HolderType.USER, daId)));
        when(daDirectory.contactsFor(any())).thenReturn(Map.of(otherDa, new DaDirectoryPort.DaContact("Sita", "8")));

        AssetView v = service.transfer(id, otherDa, "colleague", cityId, actor);

        assertThat(v.status()).isEqualTo("ASSIGNED");
        assertThat(v.currentHolderId()).isEqualTo(otherDa);
        assertThat(v.ackPending()).isTrue();
        ArgumentCaptor<AssetCustodyEvent> cap = ArgumentCaptor.forClass(AssetCustodyEvent.class);
        verify(custody).save(cap.capture());
        assertThat(cap.getValue().getEventType()).isEqualTo(AssetEventType.TRANSFERRED);
        assertThat(cap.getValue().getFromHolderId()).isEqualTo(daId);
    }

    @Test
    void acknowledge_flipsAckPending_andRecordsAck() {
        UUID id = UUID.randomUUID();
        when(assets.findByIdForUpdate(id)).thenReturn(Optional.of(asset(AssetStatus.ASSIGNED, HolderType.USER, daId)));

        AssetView v = service.acknowledge(id, daId);

        assertThat(v.ackPending()).isFalse();
        ArgumentCaptor<AssetCustodyEvent> cap = ArgumentCaptor.forClass(AssetCustodyEvent.class);
        verify(custody).save(cap.capture());
        assertThat(cap.getValue().getEventType()).isEqualTo(AssetEventType.ACKNOWLEDGED);
    }

    @Test
    void acknowledge_whenNotHolder_forbidden() {
        UUID id = UUID.randomUUID();
        when(assets.findByIdForUpdate(id)).thenReturn(Optional.of(asset(AssetStatus.ASSIGNED, HolderType.USER, daId)));
        assertThatThrownBy(() -> service.acknowledge(id, UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }

    @Test
    void reportLost_keepsHolderAsBlame_setsLost() {
        UUID id = UUID.randomUUID();
        when(assets.findByIdForUpdate(id)).thenReturn(Optional.of(asset(AssetStatus.ASSIGNED, HolderType.USER, daId)));

        AssetView v = service.reportLost(id, "not returned", cityId, actor);

        assertThat(v.status()).isEqualTo("LOST");
        assertThat(v.currentHolderId()).isEqualTo(daId);   // last known owner preserved for blame
        ArgumentCaptor<AssetCustodyEvent> cap = ArgumentCaptor.forClass(AssetCustodyEvent.class);
        verify(custody).save(cap.capture());
        assertThat(cap.getValue().getEventType()).isEqualTo(AssetEventType.REPORTED_LOST);
        assertThat(cap.getValue().getFromHolderId()).isEqualTo(daId);
    }

    @Test
    void presignPhotoUploads_whenStorageUnavailable_returnsEmpty() {
        when(storage.isAvailable()).thenReturn(false);
        List<EvidenceUpload> out = service.presignPhotoUploads(2, cityId);
        assertThat(out).isEmpty();
    }

    @Test
    void presignPhotoUploads_countOverMax_badRequest() {
        assertThatThrownBy(() -> service.presignPhotoUploads(99, cityId))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
    }

    @Test
    void selectVan_notAVehicle_badRequest() {
        UUID id = UUID.randomUUID();
        Asset scanner = asset(AssetStatus.IN_STOCK, HolderType.STATION, null);
        scanner.setCategory(AssetCategory.SCANNER);
        when(assets.findById(id)).thenReturn(Optional.of(scanner));
        assertThatThrownBy(() -> service.selectVan(daId, cityId, new SelectVanRequest(id, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
    }
}
