package com.oneday.orders.service.impl;

import com.oneday.common.port.ObjectStoragePort;
import com.oneday.orders.config.MeasurementProperties;
import com.oneday.orders.domain.MeasurementMethod;
import com.oneday.orders.domain.MeasurementSource;
import com.oneday.orders.domain.ParcelMeasurement;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.dto.EvidenceCapture;
import com.oneday.orders.dto.EvidenceUpload;
import com.oneday.orders.dto.MeasurementView;
import com.oneday.orders.repository.ParcelMeasurementRepository;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.DiscrepancyPolicy;
import com.oneday.orders.service.ParcelMeasurementService;
import com.oneday.vision.DimensionEngine;
import com.oneday.vision.MeasurementResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates first-mile dimension capture (see {@link ParcelMeasurementService}). The CV + storage
 * fetch run OUTSIDE any DB transaction; only the append-only persist ({@link MeasurementPersister})
 * is transactional. Every storage/CV failure degrades to a recorded non-OK measurement rather than
 * throwing, so pickup is never blocked.
 */
@Service
class ParcelMeasurementServiceImpl implements ParcelMeasurementService {

    private static final Logger log = LoggerFactory.getLogger(ParcelMeasurementServiceImpl.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String KEY_PREFIX = "pickup-measurements";

    private final ShipmentRepository shipmentRepository;
    private final ParcelMeasurementRepository measurementRepository;
    private final ObjectStoragePort storage;
    private final DimensionEngine engine;
    private final DiscrepancyPolicy discrepancyPolicy;
    private final MeasurementPersister persister;
    private final MeasurementProperties props;

    ParcelMeasurementServiceImpl(ShipmentRepository shipmentRepository,
                                 ParcelMeasurementRepository measurementRepository,
                                 ObjectStoragePort storage,
                                 DimensionEngine engine,
                                 DiscrepancyPolicy discrepancyPolicy,
                                 MeasurementPersister persister,
                                 MeasurementProperties props) {
        this.shipmentRepository = shipmentRepository;
        this.measurementRepository = measurementRepository;
        this.storage = storage;
        this.engine = engine;
        this.discrepancyPolicy = discrepancyPolicy;
        this.persister = persister;
        this.props = props;
    }

    @Override
    public List<EvidenceUpload> presignUploads(String shipmentRef, int count) {
        Shipment shipment = resolve(shipmentRef);
        if (count < 1 || count > props.getMaxEvidencePhotos()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "count must be between 1 and " + props.getMaxEvidencePhotos());
        }
        if (!storage.isAvailable()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "evidence storage unavailable");
        }
        List<EvidenceUpload> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String key = buildKey(shipment.getShipmentRef());
            String url = storage.presignPut(key, "image/jpeg", Duration.ofSeconds(props.getUploadUrlTtlSeconds()));
            out.add(new EvidenceUpload(key, url));
        }
        return out;
    }

    @Override
    public MeasurementView recordDaPickupMeasurement(String shipmentRef, UUID daUserId, List<EvidenceCapture> captures) {
        Shipment shipment = resolve(shipmentRef);
        if (captures == null || captures.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "no captures");
        }
        if (captures.size() > props.getMaxEvidencePhotos()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "too many captures");
        }

        // Fetch bytes + build engine captures (never throws — a fetch failure just skips that photo).
        List<String> keys = new ArrayList<>();
        List<DimensionEngine.Capture> engineCaptures = new ArrayList<>();
        boolean fetchFailed = false;
        for (EvidenceCapture c : captures) {
            String key = c.objectKey();
            requireOwnedKey(key, shipment.getShipmentRef());
            keys.add(key);
            if (!storage.isAvailable()) { fetchFailed = true; continue; }
            try {
                if (!storage.exists(key)) { fetchFailed = true; continue; }
                byte[] bytes = storage.getBytes(key);
                engineCaptures.add(new DimensionEngine.Capture(bytes, parseView(c.view())));
            } catch (RuntimeException e) {
                log.warn("Evidence fetch failed for key {}: {}", key, e.toString());
                fetchFailed = true;
            }
        }

        MeasurementResult result = runEngine(engineCaptures, fetchFailed);
        ParcelMeasurement m = toEntity(shipment, daUserId, keys, result);
        ParcelMeasurement saved = persister.persist(m);
        log.info("Recorded DA_PICKUP measurement ref={} status={} overDeclared={}",
                shipmentRef, saved.getStatus(), saved.isOverDeclared());
        return toView(saved, null);
    }

    @Override
    public List<MeasurementView> history(String shipmentRef, boolean withEvidenceUrls) {
        Shipment shipment = resolve(shipmentRef);
        return measurementRepository.findByShipmentIdOrderByCreatedAtDesc(shipment.getId()).stream()
                .map(m -> toView(m, withEvidenceUrls ? presignEvidenceGets(m) : null))
                .toList();
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private MeasurementResult runEngine(List<DimensionEngine.Capture> captures, boolean fetchFailed) {
        if (!engine.isAvailable()) {
            return MeasurementResult.failed(MeasurementResult.Status.ENGINE_UNAVAILABLE, "cv engine offline");
        }
        if (captures.isEmpty()) {
            return MeasurementResult.failed(MeasurementResult.Status.BAD_INPUT,
                    fetchFailed ? "evidence unreadable" : "no captures");
        }
        try {
            return engine.measure(captures);
        } catch (RuntimeException e) {   // defensive — engine already swallows, but never let it escape
            log.warn("Dimension engine threw unexpectedly", e);
            return MeasurementResult.failed(MeasurementResult.Status.ERROR, e.toString());
        }
    }

    private ParcelMeasurement toEntity(Shipment s, UUID daUserId, List<String> keys, MeasurementResult r) {
        ParcelMeasurement m = new ParcelMeasurement();
        m.setShipmentId(s.getId());
        m.setShipmentRef(s.getShipmentRef());
        m.setSource(MeasurementSource.DA_PICKUP);
        m.setMethod(MeasurementMethod.ARUCO);
        m.setStatus(r.status().name());
        m.setLengthCm(r.lengthCm());
        m.setWidthCm(r.widthCm());
        m.setHeightCm(r.heightCm());
        m.setVolumetricWeightGrams(volumetricGrams(r));
        m.setConfidence((float) r.confidence());
        m.setDeclaredLengthCm(s.getLengthCm());
        m.setDeclaredWidthCm(s.getWidthCm());
        m.setDeclaredHeightCm(s.getHeightCm());
        m.setEvidenceKeys(keys);
        m.setMeasuredBy(daUserId);

        if (r.isOk()) {
            double[] declared = {nz(s.getLengthCm()), nz(s.getWidthCm()), nz(s.getHeightCm())};
            Double[] measured = {r.lengthCm(), r.widthCm(), r.heightCm()};
            DiscrepancyPolicy.Verdict v = discrepancyPolicy.evaluate(declared, measured);
            m.setOverDeclared(v.overDeclared());
            m.setDiscrepancyDetail(v.detail());
        } else {
            m.setOverDeclared(false);
            m.setDiscrepancyDetail(r.detail());
        }
        return m;
    }

    private Integer volumetricGrams(MeasurementResult r) {
        if (!r.isOk() || r.lengthCm() == null || r.widthCm() == null || r.heightCm() == null) return null;
        // L*W*H (cm³) / divisor = volumetric kg → *1000 for grams. Matches M4 booking math.
        double kg = (r.lengthCm() * r.widthCm() * r.heightCm()) / props.getVolumetricDivisor();
        return (int) Math.round(kg * 1000);
    }

    private List<String> presignEvidenceGets(ParcelMeasurement m) {
        if (!storage.isAvailable() || m.getEvidenceKeys() == null) return List.of();
        List<String> urls = new ArrayList<>();
        for (String key : m.getEvidenceKeys()) {
            try {
                urls.add(storage.presignGet(key, Duration.ofSeconds(props.getViewUrlTtlSeconds())));
            } catch (RuntimeException e) {
                log.warn("Failed to presign GET for evidence key {}: {}", key, e.toString());
            }
        }
        return urls;
    }

    private MeasurementView toView(ParcelMeasurement m, List<String> evidenceUrls) {
        return new MeasurementView(
                m.getId(), m.getSource().name(), m.getMethod().name(), m.getStatus(),
                MeasurementResult.Status.OK.name().equals(m.getStatus()),
                m.getLengthCm(), m.getWidthCm(), m.getHeightCm(),
                m.getVolumetricWeightGrams(), m.getConfidence(),
                m.getDeclaredLengthCm(), m.getDeclaredWidthCm(), m.getDeclaredHeightCm(),
                m.isOverDeclared(), m.getDiscrepancyDetail(), evidenceUrls, m.getCreatedAt());
    }

    private Shipment resolve(String shipmentRef) {
        return shipmentRepository.findByShipmentRef(shipmentRef)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "shipment not found"));
    }

    private void requireOwnedKey(String key, String shipmentRef) {
        if (key == null || !key.startsWith(KEY_PREFIX + "/") || !key.contains("/" + shipmentRef + "/")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "evidence key does not belong to this shipment");
        }
    }

    private String buildKey(String shipmentRef) {
        LocalDate d = LocalDate.now(IST);
        return String.format("%s/%04d/%02d/%02d/%s/%s.jpg",
                KEY_PREFIX, d.getYear(), d.getMonthValue(), d.getDayOfMonth(), shipmentRef, UUID.randomUUID());
    }

    private static DimensionEngine.View parseView(String v) {
        if (v == null) return DimensionEngine.View.UNKNOWN;
        try {
            return DimensionEngine.View.valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return DimensionEngine.View.UNKNOWN;
        }
    }

    private static double nz(Short s) {
        return s == null ? 0 : s.doubleValue();
    }
}
