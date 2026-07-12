package com.oneday.barcode.api;

import com.oneday.barcode.service.LabelService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * M8 scan entry doors. PR2 ships {@code /label} (parcel-ID generation at first-mile pickup); the
 * generic {@code POST /api/v1/scan} for lifecycle scans lands in PR3.
 */
@RestController
@RequestMapping("/api/v1/scan")
class ScanController {

    private final LabelService labelService;

    ScanController(LabelService labelService) {
        this.labelService = labelService;
    }

    @PostMapping("/label")
    LabelResponse label(@Valid @RequestBody LabelRequest req) {
        String parcelId = labelService.generateLabel(
                req.shipmentId(), req.destCity(), req.actorId(), req.clientScanId());
        return new LabelResponse(req.shipmentId(), parcelId);
    }
}
