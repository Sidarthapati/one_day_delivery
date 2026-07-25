package com.oneday.orders.service;

import com.oneday.orders.dto.ShipmentTrackResponse;

import java.util.Optional;

/**
 * Live-tracking read model for a customer's own shipment: current position (static node or live dot),
 * the route to draw, and the milestone timeline. Scoped to the caller — a shipment the caller did not
 * book is treated as not found.
 */
public interface TrackingService {

    /**
     * @param userId      the authenticated caller's id (M1 user UUID, as a string)
     * @param shipmentRef the shipment reference
     * @return the tracking view, or empty if no such ref exists for this caller
     */
    Optional<ShipmentTrackResponse> track(String userId, String shipmentRef);
}
