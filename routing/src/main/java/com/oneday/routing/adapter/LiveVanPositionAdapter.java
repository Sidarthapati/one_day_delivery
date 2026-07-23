package com.oneday.routing.adapter;

import com.oneday.common.port.LivePosition;
import com.oneday.common.port.LiveVanPositionPort;
import com.oneday.routing.domain.ManifestItemStatus;
import com.oneday.routing.domain.VanLiveStatus;
import com.oneday.routing.domain.VanManifestItem;
import com.oneday.routing.repository.VanLiveStatusRepository;
import com.oneday.routing.repository.VanManifestItemRepository;
import com.oneday.routing.repository.VanManifestRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * M6-side implementation of {@link LiveVanPositionPort}: finds the manifest item currently on a van
 * for this parcel ({@code van_manifest_item} in LOADED/ONBOARD), walks to its {@code van_manifest}
 * for the van id, and reads that van's live row from {@code van_live_status}. Empty when the parcel
 * isn't riding a van right now or the van has no GPS fix yet.
 */
@Component
class LiveVanPositionAdapter implements LiveVanPositionPort {

    // A parcel is physically on the van only in these item states (loaded at the hub, riding onboard).
    private static final Set<ManifestItemStatus> ON_VAN = Set.of(ManifestItemStatus.LOADED, ManifestItemStatus.ONBOARD);

    private final VanManifestItemRepository itemRepository;
    private final VanManifestRepository manifestRepository;
    private final VanLiveStatusRepository liveStatusRepository;

    LiveVanPositionAdapter(VanManifestItemRepository itemRepository,
                           VanManifestRepository manifestRepository,
                           VanLiveStatusRepository liveStatusRepository) {
        this.itemRepository = itemRepository;
        this.manifestRepository = manifestRepository;
        this.liveStatusRepository = liveStatusRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LivePosition> forShipment(UUID shipmentId) {
        // parcelId == shipment UUID in v1 (routing D-001).
        Optional<VanManifestItem> onVan = itemRepository.findByParcelId(shipmentId).stream()
                .filter(i -> ON_VAN.contains(i.getStatus()))
                .max(Comparator.comparing(VanManifestItem::getUpdatedAt));
        if (onVan.isEmpty()) {
            return Optional.empty();
        }
        return manifestRepository.findById(onVan.get().getManifestId())
                .flatMap(m -> liveStatusRepository.findById(m.getVanId()))
                .filter(LiveVanPositionAdapter::hasFix)
                .map(v -> new LivePosition(
                        v.getLastLat(), v.getLastLon(), v.getLastSeenAt(), v.getMinutesLate(), v.getVanId()));
    }

    private static boolean hasFix(VanLiveStatus v) {
        return v.getLastLat() != null && v.getLastLon() != null;
    }
}
