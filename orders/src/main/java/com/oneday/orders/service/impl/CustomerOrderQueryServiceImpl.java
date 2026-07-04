package com.oneday.orders.service.impl;

import com.oneday.orders.domain.Address;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.dto.MyShipmentDetailResponse;
import com.oneday.orders.dto.MyShipmentSummaryResponse;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.CustomerOrderQueryService;
import com.oneday.orders.service.CustomerVisibleStateMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * @see CustomerOrderQueryService
 */
@Service
class CustomerOrderQueryServiceImpl implements CustomerOrderQueryService {

    private static final int MAX_LIMIT = 200;

    private final ShipmentRepository shipmentRepository;
    private final CustomerVisibleStateMapper stateMapper;

    CustomerOrderQueryServiceImpl(ShipmentRepository shipmentRepository,
                                  CustomerVisibleStateMapper stateMapper) {
        this.shipmentRepository = shipmentRepository;
        this.stateMapper = stateMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public List<MyShipmentSummaryResponse> myShipments(String userId, int limit) {
        UUID id = UserIds.parse(userId);
        if (id == null) {
            return List.of();   // unauthenticated/synthetic principal — no attributed bookings
        }
        int safeLimit = Math.min(Math.max(1, limit), MAX_LIMIT);
        Pageable pageable = PageRequest.of(0, safeLimit, Sort.by(Sort.Direction.DESC, "createdAt"));
        return shipmentRepository.findByBookedByUserId(id, pageable)
                .map(this::toSummary)
                .getContent();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MyShipmentDetailResponse> myShipmentDetail(String userId, String shipmentRef) {
        UUID id = UserIds.parse(userId);
        if (id == null) {
            return Optional.empty();
        }
        return shipmentRepository.findByShipmentRef(shipmentRef)
                .filter(s -> id.equals(s.getBookedByUserId()))   // ownership scope: not yours → not found
                .map(this::toDetail);
    }

    private MyShipmentDetailResponse toDetail(Shipment s) {
        Address o = s.getOriginAddress();
        Address d = s.getDestAddress();
        return new MyShipmentDetailResponse(
                s.getShipmentRef(), s.getCustomerType(), s.getState(), stateMapper.labelFor(s.getState()),
                s.getDeliveryType(), s.getPickupType(), s.getDropType(), s.getPaymentMode(),
                s.getSenderName(), s.getSenderPhone(), s.getSenderEmail(),
                o != null ? o.getLine1() : null, s.getOriginCity(), s.getOriginPincode(),
                o != null ? o.getLatitude() : null, o != null ? o.getLongitude() : null, s.getOriginTileId(),
                s.getReceiverName(), s.getReceiverPhone(), s.getReceiverEmail(),
                d != null ? d.getLine1() : null, s.getDestCity(), s.getDestPincode(),
                d != null ? d.getLatitude() : null, d != null ? d.getLongitude() : null, s.getDestTileId(),
                s.getWeightGrams(), s.getVolumetricWeightGrams(), s.getChargeableWeightGrams(),
                s.getDeclaredValuePaise(), s.getQuotedPricePaise(), s.getTaxPaise(), s.getTotalPricePaise(),
                s.getCreatedAt(), s.getCancelledAt());
    }

    private MyShipmentSummaryResponse toSummary(Shipment s) {
        return new MyShipmentSummaryResponse(
                s.getShipmentRef(),
                s.getCustomerType(),
                s.getState(),
                stateMapper.labelFor(s.getState()),
                s.getOriginCity(),
                s.getDestCity(),
                s.getTotalPricePaise(),
                s.getCreatedAt(),
                s.getCancelledAt());
    }
}
