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
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));

        ShipmentState state = (stateFilter == null || stateFilter.isBlank())
                ? null : parseState(stateFilter);

        Page<Shipment> result;
        if (cityScope == null) {                       // admin oversight — all cities
            result = (state == null)
                    ? shipmentRepository.findAll(pageable)
                    : shipmentRepository.findByState(state, pageable);
        } else {                                       // station manager — only their city's legs
            result = (state == null)
                    ? shipmentRepository.findByCityInvolved(cityScope, pageable)
                    : shipmentRepository.findByCityInvolvedAndState(cityScope, state, pageable);
        }

        return new ShipmentPageResponse(
                result.map(s -> toSummary(s, cityScope)).getContent(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
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
