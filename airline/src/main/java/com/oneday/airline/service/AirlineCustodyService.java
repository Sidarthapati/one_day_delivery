package com.oneday.airline.service;

import com.oneday.airline.domain.AwbParcel;
import com.oneday.airline.events.CustodyScanProducer;
import com.oneday.airline.repository.AwbParcelRepository;
import com.oneday.common.kafka.enums.ScanEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * The airport-custody actions behind gap G1: one AWB covers a whole plane, so a single console action
 * (dispatch to airport / GHA accepted / dest shuttle-in / dest received) fires the matching custody scan
 * for <em>every</em> parcel on that AWB, advancing them all through the air legs at once.
 */
@Service
public class AirlineCustodyService {

    private static final Logger log = LoggerFactory.getLogger(AirlineCustodyService.class);

    private final AwbParcelRepository awbParcelRepository;
    private final CustodyScanProducer scanProducer;

    public AirlineCustodyService(AwbParcelRepository awbParcelRepository, CustodyScanProducer scanProducer) {
        this.awbParcelRepository = awbParcelRepository;
        this.scanProducer = scanProducer;
    }

    /** Fire {@code type} for every parcel on the AWB. Returns how many parcels were scanned (0 → unknown AWB). */
    public int record(UUID awbId, ScanEventType type) {
        List<AwbParcel> parcels = awbParcelRepository.findByAwbId(awbId);
        parcels.forEach(p -> scanProducer.publish(p.getParcelId(), type));
        log.info("Custody scan {} fired for {} parcel(s) on AWB {}", type, parcels.size(), awbId);
        return parcels.size();
    }
}
