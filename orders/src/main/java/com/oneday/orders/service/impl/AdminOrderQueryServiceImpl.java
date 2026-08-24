package com.oneday.orders.service.impl;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.common.port.ShipmentScanTrailPort;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.domain.ShipmentStateHistory;
import com.oneday.orders.dto.ShipmentPageResponse;
import com.oneday.orders.dto.ShipmentSummaryResponse;
import com.oneday.orders.dto.ShipmentTimelineResponse;
import com.oneday.orders.dto.ShipmentTimelineResponse.TimelineEvent;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.repository.ShipmentStateHistoryRepository;
import com.oneday.orders.service.AdminOrderQueryService;
import com.oneday.orders.service.ShipmentCustody;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * @see AdminOrderQueryService
 */
@Service
class AdminOrderQueryServiceImpl implements AdminOrderQueryService {

    private static final int MAX_PAGE_SIZE = 200;
    // ponytail: export pages 500 at a time and stops at 50k rows — a pilot city-day is well under this;
    // raise the cap (or switch to a true streaming response) if a single export ever needs more.
    private static final int EXPORT_PAGE_SIZE = 500;
    private static final int EXPORT_MAX_ROWS = 50_000;

    private final ShipmentRepository shipmentRepository;
    private final ShipmentStateHistoryRepository stateHistoryRepository;
    private final ShipmentScanTrailPort scanTrail;

    AdminOrderQueryServiceImpl(ShipmentRepository shipmentRepository,
                               ShipmentStateHistoryRepository stateHistoryRepository,
                               ShipmentScanTrailPort scanTrail) {
        this.shipmentRepository = shipmentRepository;
        this.stateHistoryRepository = stateHistoryRepository;
        this.scanTrail = scanTrail;
    }

    @Override
    @Transactional(readOnly = true)
    public ShipmentPageResponse listShipments(String stateFilter, String cityScope, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.ASC, "id")));

        ShipmentState state = (stateFilter == null || stateFilter.isBlank())
                ? null : parseState(stateFilter);

        Page<Shipment> result = fetchPage(state, cityScope, pageable);

        return new ShipmentPageResponse(
                result.map(s -> toSummary(s, cityScope)).getContent(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    @Override
    // REPEATABLE_READ gives the whole paged export one stable snapshot, so shipments booked or
    // transitioning state between the per-page OFFSET queries can't duplicate or drop rows (the id
    // tiebreaker only orders within a single query's snapshot). Read-only + short-lived, so cheap.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public java.util.List<ShipmentSummaryResponse> exportShipments(String stateFilter, String cityScope) {
        ShipmentState state = (stateFilter == null || stateFilter.isBlank())
                ? null : parseState(stateFilter);

        java.util.List<ShipmentSummaryResponse> out = new java.util.ArrayList<>();
        for (int page = 0; out.size() < EXPORT_MAX_ROWS; page++) {
            Pageable pageable = PageRequest.of(page, EXPORT_PAGE_SIZE, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.ASC, "id")));
            Page<Shipment> batch = fetchPage(state, cityScope, pageable);
            batch.forEach(s -> out.add(toSummary(s, cityScope)));
            if (!batch.hasNext()) {
                break;
            }
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public ShipmentTimelineResponse timeline(String shipmentRef, String cityScope) {
        Shipment s = shipmentRepository.findByShipmentRef(shipmentRef)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Unknown shipment: " + shipmentRef));
        // Same read rule as the list: a station manager only sees a shipment touching their city.
        if (cityScope != null
                && !cityScope.equals(s.getOriginCity()) && !cityScope.equals(s.getDestCity())) {
            throw new ResponseStatusException(NOT_FOUND, "Unknown shipment: " + shipmentRef);
        }

        List<TimelineEvent> events = new ArrayList<>();
        for (ShipmentStateHistory h : stateHistoryRepository.findByShipmentIdOrderByOccurredAtAsc(s.getId())) {
            String detail = h.getTriggerSource() != null ? "via " + h.getTriggerSource() : null;
            if (h.getNotes() != null && !h.getNotes().isBlank()) {
                detail = detail == null ? h.getNotes() : detail + " · " + h.getNotes();
            }
            events.add(new TimelineEvent(h.getOccurredAt(), "STATE", h.getToState().name(), detail));
        }
        for (ShipmentScanTrailPort.ScanTrailEntry scan : scanTrail.trailFor(s.getId())) {
            String detail = scan.locationType() != null ? "at " + scan.locationType() : null;
            events.add(new TimelineEvent(scan.scannedAt(), "SCAN", scan.scanType(), detail));
        }
        events.sort(Comparator.comparing(TimelineEvent::at,
                Comparator.nullsLast(Comparator.naturalOrder())));

        return new ShipmentTimelineResponse(
                s.getShipmentRef(), s.getId(), s.getState(), s.getCustomerType(), s.getDeliveryType(),
                s.getOriginCity(), s.getDestCity(), s.getSenderName(), s.getReceiverName(),
                s.getChargeableWeightGrams(), s.getEtaPromised(), s.getLastScanAt(), s.getCreatedAt(),
                events);
    }

    private Page<Shipment> fetchPage(ShipmentState state, String cityScope, Pageable pageable) {
        if (cityScope == null) {                       // admin oversight — all cities
            return (state == null)
                    ? shipmentRepository.findAll(pageable)
                    : shipmentRepository.findByState(state, pageable);
        }
        // station manager — only their city's legs (origin OR dest)
        return (state == null)
                ? shipmentRepository.findByCityInvolved(cityScope, pageable)
                : shipmentRepository.findByCityInvolvedAndState(cityScope, state, pageable);
    }

    private static ShipmentState parseState(String stateFilter) {
        try {
            return ShipmentState.valueOf(stateFilter.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(BAD_REQUEST, "Unknown shipment state: " + stateFilter);
        }
    }

    private static ShipmentSummaryResponse toSummary(Shipment s, String requesterCity) {
        // Custody city = origin or dest depending on the parcel's current phase (ShipmentCustody).
        String custodyCity = ShipmentCustody.custodian(s.getState()) == ShipmentCustody.Custodian.ORIGIN
                ? s.getOriginCity()
                : s.getDestCity();
        // The viewer may act only when they are the current custodian (station manager of that city).
        boolean canAct = requesterCity != null && requesterCity.equals(custodyCity);
        return new ShipmentSummaryResponse(
                s.getShipmentRef(),
                s.getCustomerType(),
                s.getDeliveryType(),
                s.getState(),
                s.getPickupType(),
                s.getPaymentMode(),
                s.getOriginCity(),
                s.getOriginPincode(),
                s.getDestCity(),
                s.getDestPincode(),
                s.getSenderName(),
                s.getReceiverName(),
                s.getChargeableWeightGrams(),
                s.getTotalPricePaise(),
                s.getCreatedAt(),
                s.getCancelledAt(),
                custodyCity,
                canAct);
    }
}
