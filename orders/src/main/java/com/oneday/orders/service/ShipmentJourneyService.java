package com.oneday.orders.service;

import com.oneday.orders.dto.JourneyStep;

import java.util.List;
import java.util.UUID;

/**
 * Public, read-only access to a shipment's full internal state trail (every transition, timestamped,
 * with actor + source + notes). The ops-facing counterpart to {@link TrackingService}'s customer view;
 * consumed cross-module (M11 exceptions) by importing this interface + {@link JourneyStep}.
 */
public interface ShipmentJourneyService {

    /** The ordered state trail for a shipment (oldest first); empty if the shipment is unknown. */
    List<JourneyStep> journey(UUID shipmentId);
}
