package com.oneday.orders.api;

import com.oneday.auth.security.AuthUserDetails;
import com.oneday.orders.dto.EvidenceUpload;
import com.oneday.orders.dto.MeasurementSubmitRequest;
import com.oneday.orders.dto.MeasurementView;
import com.oneday.orders.service.ParcelMeasurementService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * First-mile "scan dimensions" endpoints for the DA app. The DA presigns upload slots, uploads the
 * evidence photos straight to object storage, then submits the keys for authoritative server-side
 * measurement. A read endpoint (ADMIN) backs the ops/dispute console.
 *
 * <p>On {@code /internal/v1/} behind the JWT security filter, mirroring {@link PickupOtpController}.
 * DA actions require the {@code DELIVERY_ASSOCIATE} role (ADMIN allowed); the console read is
 * ADMIN-only.</p>
 */
@RestController
@RequestMapping("/internal/v1/shipments/{ref}/measurement")
public class ParcelMeasurementController {

    private final ParcelMeasurementService measurementService;

    public ParcelMeasurementController(ParcelMeasurementService measurementService) {
        this.measurementService = measurementService;
    }

    /** Presign {@code count} (default 2) upload slots for this shipment's evidence photos. */
    @PostMapping("/upload-urls")
    public List<EvidenceUpload> uploadUrls(@PathVariable String ref,
                                           @AuthenticationPrincipal AuthUserDetails principal,
                                           @RequestParam(defaultValue = "2") int count) {
        Authz.requireRole(principal, "DELIVERY_ASSOCIATE");
        return measurementService.presignUploads(ref, count, principal.getUserId(), isAdmin(principal));
    }

    /** Submit uploaded evidence for measurement; returns the measured dims + over-declared verdict. */
    @PostMapping
    public MeasurementView submit(@PathVariable String ref,
                                  @AuthenticationPrincipal AuthUserDetails principal,
                                  @Valid @RequestBody MeasurementSubmitRequest request) {
        Authz.requireRole(principal, "DELIVERY_ASSOCIATE");
        UUID daUserId = principal.getUserId();
        return measurementService.recordDaPickupMeasurement(ref, daUserId, isAdmin(principal), request.captures());
    }

    private static boolean isAdmin(AuthUserDetails principal) {
        return principal != null && Authz.ADMIN.equals(principal.getUser().getRole().getName());
    }

    /** All measurements for a shipment, with short-lived evidence photo URLs (ops/dispute console). */
    @GetMapping
    public List<MeasurementView> history(@PathVariable String ref,
                                         @AuthenticationPrincipal AuthUserDetails principal) {
        Authz.requireRole(principal);   // ADMIN only
        return measurementService.history(ref, true);
    }
}
