package com.oneday.hub.service.port;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Local bag-label builder until M8 ships (M7-D-010). The dual number is {@code flightNo|standNo}. */
@Component
class LocalBarcodePort implements BarcodePort {

    @Override
    public String buildBagLabel(String flightNo, String standNo) {
        return flightNo + "|" + standNo;
    }

    @Override
    public Optional<Instant> latestScanAt(UUID parcelId, String scanPoint) {
        return Optional.empty();
    }
}
