package com.oneday.orders.service;

import com.oneday.orders.dto.ReviseEtaResponse;

import java.time.Instant;

/**
 * Revises a shipment's delivery ETA after booking and, when the new ETA slips past the promised one,
 * notifies the customer (the "delay mail": tell them the new ETA and that they can wait or cancel).
 * The intended automatic caller is the hub-scan recompute at AT_ORIGIN_HUB; today the ops console
 * drives it explicitly.
 */
public interface ShipmentEtaService {

    /**
     * @param shipmentRef the shipment to revise
     * @param newEta      the revised expected-delivery instant
     * @param reason      optional note (audit / customer context)
     * @param actorUserId who triggered it (M1 user id), for the audit trail
     */
    ReviseEtaResponse reviseEta(String shipmentRef, Instant newEta, String reason, String actorUserId);
}
