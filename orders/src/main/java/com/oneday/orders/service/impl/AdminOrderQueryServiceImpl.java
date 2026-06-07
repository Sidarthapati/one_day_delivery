package com.oneday.orders.service.impl;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.dto.ShipmentPageResponse;
import com.oneday.orders.dto.ShipmentSummaryResponse;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.AdminOrderQueryService;
import com.oneday.orders.service.ShipmentCustody;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * @see AdminOrderQueryService
 */
@Service
class AdminOrderQueryServiceImpl implements AdminOrderQueryService {

    private static final int MAX_PAGE_SIZE = 200;

    private final ShipmentRepository shipmentRepository;

    AdminOrderQueryServiceImpl(ShipmentRepository shipmentRepository) {
        this.shipmentRepository = shipmentRepository;
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
