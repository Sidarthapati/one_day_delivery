package com.oneday.orders.service.impl;

import com.oneday.orders.domain.Shipment;
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
