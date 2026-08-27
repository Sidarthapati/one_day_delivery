package com.oneday.assets.service.impl;

import com.oneday.assets.config.AssetProperties;
import com.oneday.assets.domain.Asset;
import com.oneday.assets.domain.AssetCategory;
import com.oneday.assets.domain.AssetCondition;
import com.oneday.assets.domain.AssetCustodyEvent;
import com.oneday.assets.domain.AssetEventType;
import com.oneday.assets.domain.AssetStatus;
import com.oneday.assets.domain.HolderType;
import com.oneday.assets.dto.AssetCustodyEventView;
import com.oneday.assets.dto.AssetView;
import com.oneday.assets.dto.EvidenceUpload;
import com.oneday.assets.dto.RegisterAssetRequest;
import com.oneday.assets.dto.SelectVanRequest;
import com.oneday.assets.events.AssetCustodyChanged;
import com.oneday.assets.repository.AssetCustodyEventRepository;
import com.oneday.assets.repository.AssetRepository;
import com.oneday.assets.service.AssetService;
import com.oneday.common.port.DaDirectoryPort;
import com.oneday.common.port.ObjectStoragePort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** See {@link AssetService}. Every custody move is a locked pointer update + one append-only ledger row. */
@Service
@Transactional
class AssetServiceImpl implements AssetService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String PHOTO_PREFIX = "asset-photos";

    private final AssetRepository assets;
    private final AssetCustodyEventRepository custody;
    private final ObjectStoragePort storage;
    private final AssetProperties props;
    private final DaDirectoryPort daDirectory;
    private final ApplicationEventPublisher appEvents;

    AssetServiceImpl(AssetRepository assets, AssetCustodyEventRepository custody, ObjectStoragePort storage,
                     AssetProperties props, DaDirectoryPort daDirectory, ApplicationEventPublisher appEvents) {
        this.assets = assets;
        this.custody = custody;
        this.storage = storage;
        this.props = props;
        this.daDirectory = daDirectory;
        this.appEvents = appEvents;
    }

    // ── Photos ───────────────────────────────────────────────────────

    @Override
    public List<EvidenceUpload> presignPhotoUploads(int count, UUID cityId) {
        if (count < 1 || count > props.getMaxPhotos()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "count must be between 1 and " + props.getMaxPhotos());
        }
        if (!storage.isAvailable()) {
            return List.of();   // degrade: registration still works with no photos
        }
        List<EvidenceUpload> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String key = buildPhotoKey(cityId);
            String url = storage.presignPut(key, "image/jpeg", Duration.ofSeconds(props.getUploadUrlTtlSeconds()));
            out.add(new EvidenceUpload(key, url));
        }
        return out;
    }

    // ── Register ─────────────────────────────────────────────────────

    @Override
    public AssetView register(RegisterAssetRequest req, UUID cityId, UUID actor) {
        String tag = req.assetTag().trim();
        if (assets.existsByAssetTag(tag)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "asset tag already exists: " + tag);
        }
        List<String> photoKeys = null;
        if (req.photoKeys() != null && !req.photoKeys().isEmpty()) {
            if (req.photoKeys().size() > props.getMaxPhotos()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "at most " + props.getMaxPhotos() + " photos");
            }
            for (String key : req.photoKeys()) {
                requireOwnedKey(key, cityId);
            }
            photoKeys = List.copyOf(req.photoKeys());
        }

        Asset a = new Asset();
        a.setAssetTag(tag);
        a.setCategory(req.category());
        a.setAssetType(req.assetType().trim());
        a.setName(req.name().trim());
        a.setDescription(req.description());
        a.setMakeModel(req.makeModel());
        a.setSerialNumber(req.serialNumber());
        a.setRegistrationNumber(req.registrationNumber());
        a.setCityId(cityId);
        a.setStatus(AssetStatus.IN_STOCK);
        a.setCondition(req.condition() != null ? req.condition() : AssetCondition.GOOD);
        a.setCurrentHolderType(HolderType.STATION);
        a.setHeldSince(Instant.now());
        a.setPhotoKeys(photoKeys);
        Asset saved;
        try {
            saved = assets.save(a);
            assets.flush();   // surface a concurrent duplicate-tag insert here, not at commit
        } catch (DataIntegrityViolationException dup) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "asset tag already exists: " + tag);
        }

        recordEvent(saved, AssetEventType.REGISTERED, null, null, null,
                HolderType.STATION, null, null, saved.getCondition(), "registered", actor);
        return view(saved, true);
    }

    // ── Reads ────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<AssetView> listByCity(UUID cityId, AssetStatus status, AssetCategory category) {
        List<Asset> rows;
        if (status != null && category != null) {
            rows = assets.findByCityIdAndCategoryAndStatusOrderByCreatedAtDesc(cityId, category, status);
        } else if (status != null) {
            rows = assets.findByCityIdAndStatusOrderByCreatedAtDesc(cityId, status);
        } else if (category != null) {
            rows = assets.findByCityIdAndCategoryOrderByCreatedAtDesc(cityId, category);
        } else {
            rows = assets.findByCityIdOrderByCreatedAtDesc(cityId);
        }
        return rows.stream().map(a -> view(a, false)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public AssetView get(UUID assetId, UUID scopeCityId) {
        return view(read(assetId, scopeCityId), true);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AssetCustodyEventView> history(UUID assetId, UUID scopeCityId) {
        read(assetId, scopeCityId);   // existence + scope
        return custody.findByAssetIdOrderByRecordedAtAsc(assetId).stream().map(this::eventView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AssetView> reconciliation(UUID cityId) {
        return assets.findByCityIdAndStatusInOrderByHeldSinceAsc(
                        cityId, List.of(AssetStatus.ASSIGNED, AssetStatus.IN_MAINTENANCE))
                .stream().map(a -> view(a, false)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AssetView> heldBy(UUID daId) {
        return assets.findByCurrentHolderTypeAndCurrentHolderIdAndStatusOrderByHeldSinceDesc(
                HolderType.USER, daId, AssetStatus.ASSIGNED).stream().map(a -> view(a, false)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AssetView> availableVans(UUID cityId) {
        return assets.findByCityIdAndCategoryAndStatusOrderByCreatedAtDesc(
                cityId, AssetCategory.VEHICLE, AssetStatus.IN_STOCK).stream().map(a -> view(a, false)).toList();
    }

    // ── Custody moves (station manager) ──────────────────────────────

    @Override
    public AssetView issue(UUID assetId, UUID toDaId, String reason, UUID scopeCityId, UUID actor) {
        Asset a = lock(assetId, scopeCityId);
        if (a.getStatus() != AssetStatus.IN_STOCK) {
            throw conflict("asset is " + a.getStatus() + " — only an IN_STOCK asset can be issued");
        }
        HolderType fromType = a.getCurrentHolderType();
        UUID fromId = a.getCurrentHolderId();
        String fromName = a.getCurrentHolderName();
        String daName = daName(toDaId);
        moveToUser(a, toDaId, daName, AssetStatus.ASSIGNED);
        recordEvent(a, AssetEventType.ISSUED, fromType, fromId, fromName,
                HolderType.USER, toDaId, daName, null, reason, actor);
        return view(a, false);
    }

    @Override
    public AssetView returnToStation(UUID assetId, AssetCondition condition, String reason, UUID scopeCityId, UUID actor) {
        Asset a = lock(assetId, scopeCityId);
        if (a.getStatus() != AssetStatus.ASSIGNED) {
            throw conflict("asset is " + a.getStatus() + " — only an ASSIGNED asset can be returned");
        }
        HolderType fromType = a.getCurrentHolderType();
        UUID fromId = a.getCurrentHolderId();
        String fromName = a.getCurrentHolderName();
        if (condition != null) a.setCondition(condition);
        moveToStation(a, AssetStatus.IN_STOCK);
        recordEvent(a, AssetEventType.RETURNED, fromType, fromId, fromName,
                HolderType.STATION, null, null, condition, reason, actor);
        return view(a, false);
    }

    @Override
    public AssetView transfer(UUID assetId, UUID toDaId, String reason, UUID scopeCityId, UUID actor) {
        Asset a = lock(assetId, scopeCityId);
        if (a.getStatus() != AssetStatus.ASSIGNED) {
            throw conflict("asset is " + a.getStatus() + " — only an ASSIGNED asset can be transferred");
        }
        if (toDaId.equals(a.getCurrentHolderId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "asset is already held by that DA");
        }
        UUID fromId = a.getCurrentHolderId();
        String fromName = a.getCurrentHolderName();
        String daName = daName(toDaId);
        moveToUser(a, toDaId, daName, AssetStatus.ASSIGNED);
        recordEvent(a, AssetEventType.TRANSFERRED, HolderType.USER, fromId, fromName,
                HolderType.USER, toDaId, daName, null, reason, actor);
        return view(a, false);
    }

    @Override
    public AssetView sendToMaintenance(UUID assetId, String reason, UUID scopeCityId, UUID actor) {
        Asset a = lock(assetId, scopeCityId);
        if (a.getStatus() == AssetStatus.RETIRED) throw conflict("asset is RETIRED");
        HolderType fromType = a.getCurrentHolderType();
        UUID fromId = a.getCurrentHolderId();
        String fromName = a.getCurrentHolderName();
        a.setStatus(AssetStatus.IN_MAINTENANCE);
        a.setCurrentHolderType(HolderType.VENDOR);
        a.setCurrentHolderId(null);
        a.setCurrentHolderName("Maintenance");
        a.setHeldSince(Instant.now());
        a.setAckPending(false);
        recordEvent(a, AssetEventType.SENT_TO_MAINTENANCE, fromType, fromId, fromName,
                HolderType.VENDOR, null, "Maintenance", null, reason, actor);
        return view(a, false);
    }

    @Override
    public AssetView returnFromMaintenance(UUID assetId, AssetCondition condition, String reason, UUID scopeCityId, UUID actor) {
        Asset a = lock(assetId, scopeCityId);
        if (a.getStatus() != AssetStatus.IN_MAINTENANCE) throw conflict("asset is not IN_MAINTENANCE");
        if (condition != null) a.setCondition(condition);
        moveToStation(a, AssetStatus.IN_STOCK);
        recordEvent(a, AssetEventType.RETURNED_FROM_MAINTENANCE, HolderType.VENDOR, null, "Maintenance",
                HolderType.STATION, null, null, condition, reason, actor);
        return view(a, false);
    }

    @Override
    public AssetView reportLost(UUID assetId, String reason, UUID scopeCityId, UUID actor) {
        Asset a = lock(assetId, scopeCityId);
        if (a.getStatus() == AssetStatus.RETIRED) throw conflict("asset is RETIRED");
        // Keep the current holder on the row as the blame record; only the status changes.
        a.setStatus(AssetStatus.LOST);
        recordEvent(a, AssetEventType.REPORTED_LOST, a.getCurrentHolderType(), a.getCurrentHolderId(),
                a.getCurrentHolderName(), a.getCurrentHolderType(), a.getCurrentHolderId(),
                a.getCurrentHolderName(), null, reason, actor);
        return view(a, false);
    }

    @Override
    public AssetView reportDamaged(UUID assetId, String reason, UUID scopeCityId, UUID actor) {
        Asset a = lock(assetId, scopeCityId);
        if (a.getStatus() == AssetStatus.RETIRED) throw conflict("asset is RETIRED");
        a.setStatus(AssetStatus.DAMAGED);
        a.setCondition(AssetCondition.DAMAGED);
        recordEvent(a, AssetEventType.REPORTED_DAMAGED, a.getCurrentHolderType(), a.getCurrentHolderId(),
                a.getCurrentHolderName(), a.getCurrentHolderType(), a.getCurrentHolderId(),
                a.getCurrentHolderName(), AssetCondition.DAMAGED, reason, actor);
        return view(a, false);
    }

    @Override
    public AssetView recover(UUID assetId, String reason, UUID scopeCityId, UUID actor) {
        Asset a = lock(assetId, scopeCityId);
        if (a.getStatus() != AssetStatus.LOST && a.getStatus() != AssetStatus.DAMAGED) {
            throw conflict("only a LOST or DAMAGED asset can be recovered");
        }
        HolderType fromType = a.getCurrentHolderType();
        UUID fromId = a.getCurrentHolderId();
        String fromName = a.getCurrentHolderName();
        moveToStation(a, AssetStatus.IN_STOCK);
        recordEvent(a, AssetEventType.RECOVERED, fromType, fromId, fromName,
                HolderType.STATION, null, null, null, reason, actor);
        return view(a, false);
    }

    @Override
    public AssetView decommission(UUID assetId, String reason, UUID scopeCityId, UUID actor) {
        Asset a = lock(assetId, scopeCityId);
        if (a.getStatus() == AssetStatus.RETIRED) throw conflict("asset is already RETIRED");
        HolderType fromType = a.getCurrentHolderType();
        UUID fromId = a.getCurrentHolderId();
        String fromName = a.getCurrentHolderName();
        moveToStation(a, AssetStatus.RETIRED);
        a.setActive(false);
        recordEvent(a, AssetEventType.DECOMMISSIONED, fromType, fromId, fromName,
                HolderType.STATION, null, null, null, reason, actor);
        return view(a, false);
    }

    // ── DA-self ──────────────────────────────────────────────────────

    @Override
    public AssetView acknowledge(UUID assetId, UUID byDaId) {
        Asset a = lock(assetId, null);
        if (a.getCurrentHolderType() != HolderType.USER || !byDaId.equals(a.getCurrentHolderId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "you do not hold this asset");
        }
        if (!a.isAckPending()) {
            return view(a, false);   // idempotent — already acknowledged
        }
        a.setAckPending(false);
        recordEvent(a, AssetEventType.ACKNOWLEDGED, HolderType.USER, byDaId, a.getCurrentHolderName(),
                HolderType.USER, byDaId, a.getCurrentHolderName(), null, "receipt confirmed", byDaId);
        return view(a, false);
    }

    @Override
    public AssetView selectVan(UUID daId, UUID daCityId, SelectVanRequest req) {
        Asset van = resolveVan(req, daCityId);
        // Idempotent: re-selecting the van you already hold just returns it (no 409).
        if (van.getCurrentHolderType() == HolderType.USER && daId.equals(van.getCurrentHolderId())
                && van.getStatus() == AssetStatus.ASSIGNED) {
            return view(van, false);
        }
        // Free any van the DA is still holding before taking a new one.
        for (Asset held : heldVehicles(daId)) {
            if (!held.getId().equals(van.getId())) {
                returnToStation(held.getId(), null, "auto-return on van switch", held.getCityId(), daId);
            }
        }
        if (van.getStatus() != AssetStatus.IN_STOCK) {
            throw conflict("van is " + van.getStatus() + " — not available");
        }
        return issue(van.getId(), daId, "self-selected for shift", daCityId, daId);
    }

    @Override
    public AssetView returnVan(UUID daId) {
        List<Asset> vans = heldVehicles(daId);
        if (vans.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no van assigned to you");
        }
        Asset v = vans.get(0);
        return returnToStation(v.getId(), null, "returned by DA", v.getCityId(), daId);
    }

    // ── internals ────────────────────────────────────────────────────

    private Asset resolveVan(SelectVanRequest req, UUID daCityId) {
        Asset van;
        if (req.assetId() != null) {
            van = read(req.assetId(), daCityId);
        } else if (req.registrationNumber() != null && !req.registrationNumber().isBlank()) {
            van = assets.findByCityIdAndRegistrationNumberIgnoreCase(daCityId, req.registrationNumber().trim())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such van in your city"));
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "assetId or registrationNumber required");
        }
        if (van.getCategory() != AssetCategory.VEHICLE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "asset is not a vehicle");
        }
        return van;
    }

    private List<Asset> heldVehicles(UUID daId) {
        return assets.findByCurrentHolderTypeAndCurrentHolderIdAndStatusOrderByHeldSinceDesc(
                        HolderType.USER, daId, AssetStatus.ASSIGNED).stream()
                .filter(a -> a.getCategory() == AssetCategory.VEHICLE).toList();
    }

    private void moveToUser(Asset a, UUID daId, String daName, AssetStatus status) {
        a.setStatus(status);
        a.setCurrentHolderType(HolderType.USER);
        a.setCurrentHolderId(daId);
        a.setCurrentHolderName(daName);
        a.setHeldSince(Instant.now());
        a.setAckPending(true);
    }

    private void moveToStation(Asset a, AssetStatus status) {
        a.setStatus(status);
        a.setCurrentHolderType(HolderType.STATION);
        a.setCurrentHolderId(null);
        a.setCurrentHolderName(null);
        a.setHeldSince(Instant.now());
        a.setAckPending(false);
    }

    private void recordEvent(Asset a, AssetEventType type, HolderType fromType, UUID fromId, String fromName,
                             HolderType toType, UUID toId, String toName, AssetCondition condition,
                             String reason, UUID actor) {
        Instant now = Instant.now();
        custody.save(AssetCustodyEvent.builder()
                .assetId(a.getId()).eventType(type)
                .fromHolderType(fromType).fromHolderId(fromId).fromHolderName(fromName)
                .toHolderType(toType).toHolderId(toId).toHolderName(toName)
                .condition(condition).actorId(actor).reason(reason).cityId(a.getCityId())
                .occurredAt(now).build());
        appEvents.publishEvent(new AssetCustodyChanged(UUID.randomUUID(), type, now,
                a.getId(), a.getAssetTag(), a.getCityId(), a.getStatus(),
                fromType, fromId, toType, toId, actor));
    }

    private Asset lock(UUID id, UUID scopeCityId) {
        Asset a = assets.findByIdForUpdate(id).orElseThrow(this::notFound);
        assertScope(a, scopeCityId);
        return a;
    }

    private Asset read(UUID id, UUID scopeCityId) {
        Asset a = assets.findById(id).orElseThrow(this::notFound);
        assertScope(a, scopeCityId);
        return a;
    }

    private void assertScope(Asset a, UUID scopeCityId) {
        if (scopeCityId != null && !scopeCityId.equals(a.getCityId())) {
            throw notFound();   // 404 rather than 403 so cross-city assets aren't enumerable
        }
    }

    private String daName(UUID daId) {
        DaDirectoryPort.DaContact c = daDirectory.contactsFor(Set.of(daId)).get(daId);
        if (c == null || c.name() == null) {
            // Don't persist a placeholder into custody state / the append-only ledger.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown delivery associate");
        }
        return c.name();
    }

    private String buildPhotoKey(UUID cityId) {
        LocalDate d = LocalDate.now(IST);
        return String.format("%s/%04d/%02d/%02d/%s/%s.jpg",
                PHOTO_PREFIX, d.getYear(), d.getMonthValue(), d.getDayOfMonth(), cityId, UUID.randomUUID());
    }

    private void requireOwnedKey(String key, UUID cityId) {
        if (key == null || !key.startsWith(PHOTO_PREFIX + "/") || !key.contains("/" + cityId + "/")
                || key.chars().anyMatch(ch -> ch < 0x20)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "photo key does not belong to this city");
        }
    }

    private List<String> presignGets(List<String> keys) {
        if (!storage.isAvailable() || keys == null || keys.isEmpty()) return null;
        List<String> urls = new ArrayList<>(keys.size());
        for (String key : keys) {
            try {
                urls.add(storage.presignGet(key, Duration.ofSeconds(props.getViewUrlTtlSeconds())));
            } catch (RuntimeException ignored) {
                // a broken key shouldn't fail the whole read
            }
        }
        return urls.isEmpty() ? null : urls;
    }

    private AssetView view(Asset a, boolean withPhotos) {
        return new AssetView(
                a.getId(), a.getAssetTag(), a.getCategory().name(), a.getAssetType(),
                a.getTrackingMode().name(), a.getName(), a.getDescription(), a.getMakeModel(),
                a.getSerialNumber(), a.getRegistrationNumber(), a.getCityId(), a.getStatus().name(),
                a.getCondition().name(),
                a.getCurrentHolderType() != null ? a.getCurrentHolderType().name() : null,
                a.getCurrentHolderId(), a.getCurrentHolderName(), a.getHeldSince(), a.isAckPending(),
                withPhotos ? presignGets(a.getPhotoKeys()) : null, a.getCreatedAt(), a.getUpdatedAt());
    }

    private AssetCustodyEventView eventView(AssetCustodyEvent e) {
        return new AssetCustodyEventView(
                e.getId(), e.getEventType().name(),
                e.getFromHolderType() != null ? e.getFromHolderType().name() : null, e.getFromHolderName(),
                e.getToHolderType() != null ? e.getToHolderType().name() : null, e.getToHolderName(),
                e.getCondition() != null ? e.getCondition().name() : null, e.getActorId(), e.getReason(),
                e.getOccurredAt(), e.getRecordedAt());
    }

    private ResponseStatusException conflict(String msg) {
        return new ResponseStatusException(HttpStatus.CONFLICT, msg);
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "asset not found");
    }
}
