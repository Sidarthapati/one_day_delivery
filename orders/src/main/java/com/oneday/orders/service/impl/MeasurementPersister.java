package com.oneday.orders.service.impl;

import com.oneday.orders.domain.ParcelMeasurement;
import com.oneday.orders.events.ParcelMeasured;
import com.oneday.orders.repository.ParcelMeasurementRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * The short transactional step of a measurement: persist the append-only row and publish the
 * in-process {@link ParcelMeasured} (relayed to the bus AFTER_COMMIT). Kept separate so the slow CV
 * work runs OUTSIDE any DB transaction — no connection is held during OpenCV.
 */
@Component
class MeasurementPersister {

    private final ParcelMeasurementRepository repository;
    private final ApplicationEventPublisher events;

    MeasurementPersister(ParcelMeasurementRepository repository, ApplicationEventPublisher events) {
        this.repository = repository;
        this.events = events;
    }

    @Transactional
    ParcelMeasurement persist(ParcelMeasurement m) {
        ParcelMeasurement saved = repository.save(m);
        events.publishEvent(new ParcelMeasured(
                saved.getId(), saved.getShipmentId(), saved.getShipmentRef(),
                saved.getSource().name(), saved.getStatus(),
                saved.getLengthCm(), saved.getWidthCm(), saved.getHeightCm(),
                saved.getVolumetricWeightGrams(),
                saved.getDeclaredLengthCm(), saved.getDeclaredWidthCm(), saved.getDeclaredHeightCm(),
                saved.isOverDeclared(), saved.getDiscrepancyDetail(), saved.getMeasuredBy(),
                Instant.now()));
        return saved;
    }
}
