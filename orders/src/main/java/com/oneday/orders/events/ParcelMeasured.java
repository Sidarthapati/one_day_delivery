package com.oneday.orders.events;

import java.time.Instant;
import java.util.UUID;

/**
 * In-process event published within the measurement transaction. {@link MeasurementEventProducer}
 * relays it to the bus AFTER_COMMIT, so a rolled-back measurement never emits a phantom event.
 */
public record ParcelMeasured(
        UUID measurementId,
        UUID shipmentId,
        String shipmentRef,
        String source,
        String status,
        Double lengthCm,
        Double widthCm,
        Double heightCm,
        Integer volumetricWeightGrams,
        Short declaredLengthCm,
        Short declaredWidthCm,
        Short declaredHeightCm,
        boolean overDeclared,
        String discrepancyDetail,
        UUID measuredBy,
        Instant occurredAt) {}
