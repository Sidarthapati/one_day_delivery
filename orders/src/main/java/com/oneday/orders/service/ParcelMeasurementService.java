package com.oneday.orders.service;

import com.oneday.orders.dto.EvidenceCapture;
import com.oneday.orders.dto.EvidenceUpload;
import com.oneday.orders.dto.MeasurementView;

import java.util.List;
import java.util.UUID;

/**
 * First-mile parcel-dimension capture. The DA app presigns upload slots, uploads photos straight to
 * object storage, then submits the keys; the server measures authoritatively (OpenCV/ArUco), records
 * an append-only observation, and flags any over-declaration against the customer's booking dims.
 *
 * <p>Best-effort by design: storage or CV failure never throws to the caller — the measurement is
 * still recorded (with a non-OK status) so the pickup flow is unaffected.</p>
 */
public interface ParcelMeasurementService {

    /** Presign {@code count} upload slots for a shipment's evidence photos. */
    List<EvidenceUpload> presignUploads(String shipmentRef, int count);

    /** Measure from the uploaded evidence and record the observation (source = DA_PICKUP). */
    MeasurementView recordDaPickupMeasurement(String shipmentRef, UUID daUserId, List<EvidenceCapture> captures);

    /** All recorded measurements for a shipment, newest first. Evidence GET URLs when requested. */
    List<MeasurementView> history(String shipmentRef, boolean withEvidenceUrls);
}
