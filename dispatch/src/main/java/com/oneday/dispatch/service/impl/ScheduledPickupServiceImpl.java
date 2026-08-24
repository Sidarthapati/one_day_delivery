package com.oneday.dispatch.service.impl;

import com.oneday.common.domain.PickupSlots;
import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.domain.ScheduledPickup;
import com.oneday.dispatch.repository.ScheduledPickupRepository;
import com.oneday.dispatch.service.DispatchService;
import com.oneday.dispatch.service.ScheduledPickupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
class ScheduledPickupServiceImpl implements ScheduledPickupService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledPickupServiceImpl.class);
    private static final String HELD = "HELD";

    private final ScheduledPickupRepository repository;
    private final DispatchService dispatchService;
    private final DispatchProperties props;

    ScheduledPickupServiceImpl(ScheduledPickupRepository repository,
                               DispatchService dispatchService,
                               DispatchProperties props) {
        this.repository = repository;
        this.dispatchService = dispatchService;
        this.props = props;
    }

    @Override
    @Transactional
    public boolean holdIfNotDue(UUID shipmentId, UUID cityId, UUID tileId, double lat, double lon,
                                String paymentMode, Instant slotStart, Instant slotEnd,
                                UUID orderId, String orderRef) {
        Instant now = Instant.now();
        Instant releaseAt = slotStart != null
                ? slotStart.minus(props.getPickup().getReleaseLeadMinutes(), ChronoUnit.MINUTES)
                : PickupSlots.nextOperatingStart(now);   // ASAP: now if within hours, else next window

        if (!releaseAt.isAfter(now)) {
            return false;   // already due — caller assigns immediately
        }
        // Idempotent on redelivery: an existing live hold means we've already parked this shipment.
        if (repository.findByShipmentIdAndStatus(shipmentId, HELD).isPresent()) {
            return true;
        }
        ScheduledPickup sp = new ScheduledPickup();
        sp.setShipmentId(shipmentId);
        sp.setOrderId(orderId);
        sp.setOrderRef(orderRef);
        sp.setCityId(cityId);
        sp.setTileId(tileId);
        sp.setPickupLat(lat);
        sp.setPickupLon(lon);
        sp.setPaymentMode(paymentMode);
        sp.setSlotStart(slotStart);
        sp.setSlotEnd(slotEnd);
        sp.setReleaseAt(releaseAt);
        sp.setStatus(HELD);
        repository.save(sp);
        log.debug("Held shipment {} until {} (slot start {})", shipmentId, releaseAt, slotStart);
        return true;
    }

    @Override
    @Transactional
    public void cancel(UUID shipmentId) {
        repository.findByShipmentIdAndStatus(shipmentId, HELD).ifPresent(sp -> {
            sp.setStatus("CANCELLED");
            repository.save(sp);
        });
    }

    @Override
    @Transactional
    public int releaseDue() {
        Instant now = Instant.now();
        List<ScheduledPickup> due = repository.findByStatusAndReleaseAtLessThanEqual(HELD, now);
        for (ScheduledPickup sp : due) {
            dispatchService.assignPickup(sp.getShipmentId(), sp.getCityId(),
                    sp.getPickupLat(), sp.getPickupLon(), sp.getTileId(), sp.getPaymentMode(),
                    sp.getOrderId(), sp.getOrderRef());
            sp.setStatus("RELEASED");
            sp.setReleasedAt(now);
        }
        if (!due.isEmpty()) {
            repository.saveAll(due);
        }
        return due.size();
    }
}
