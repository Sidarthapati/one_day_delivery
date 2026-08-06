package com.oneday.orders.service.impl;

import com.oneday.common.domain.enums.CustomerType;
import com.oneday.orders.domain.Address;
import com.oneday.orders.domain.B2bAccount;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.dto.MyShipmentDetailResponse;
import com.oneday.orders.dto.MyShipmentSummaryResponse;
import com.oneday.orders.dto.ShipmentLabelResponse;
import com.oneday.orders.repository.B2bAccountRepository;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.CustomerOrderQueryService;
import com.oneday.orders.service.CustomerVisibleStateMapper;
import com.oneday.orders.service.port.FlightTrackingPort;
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
    private final FlightTrackingPort flightTrackingPort;
    private final B2bAccountRepository b2bAccountRepository;

    CustomerOrderQueryServiceImpl(ShipmentRepository shipmentRepository,
                                  CustomerVisibleStateMapper stateMapper,
                                  FlightTrackingPort flightTrackingPort,
                                  B2bAccountRepository b2bAccountRepository) {
        this.shipmentRepository = shipmentRepository;
        this.stateMapper = stateMapper;
        this.flightTrackingPort = flightTrackingPort;
        this.b2bAccountRepository = b2bAccountRepository;
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

    @Override
    @Transactional(readOnly = true)
    public Optional<ShipmentLabelResponse> shipmentLabel(String userId, String shipmentRef) {
        UUID id = UserIds.parse(userId);
        if (id == null) {
            return Optional.empty();
        }
        return shipmentRepository.findByShipmentRef(shipmentRef)
                .filter(s -> id.equals(s.getBookedByUserId()))
                .map(this::toLabel);
    }

    private ShipmentLabelResponse toLabel(Shipment s) {
        Address o = s.getOriginAddress();
        Address d = s.getDestAddress();
        // Seller block from the B2B account (name + GSTIN); null for B2C/C2C.
        String sellerName = null;
        String sellerGstin = null;
        if (s.getCustomerType() == CustomerType.B2B && s.getB2bAccountId() != null) {
            B2bAccount acct = b2bAccountRepository.findById(s.getB2bAccountId()).orElse(null);
            if (acct != null) {
                sellerName = acct.getBrandName() != null ? acct.getBrandName() : acct.getAccountName();
                sellerGstin = acct.getGstin();
            }
        }
        return new ShipmentLabelResponse(
                s.getShipmentRef(), s.getShipmentRef(),
                s.getCustomerType(), s.getDeliveryType(), s.getPickupType(), s.getDropType(),
                s.getOriginCity(), s.getDestCity(),
                s.getSenderName(), s.getSenderPhone(), formatAddress(o), s.getOriginPincode(),
                s.getReceiverName(), s.getReceiverPhone(), formatAddress(d), s.getDestPincode(),
                s.getWeightGrams(), s.getChargeableWeightGrams(), s.getVolumetricWeightGrams(),
                s.getDeclaredValuePaise(), s.getCodAmountPaise(),
                sellerName, sellerGstin, s.getEwayBillNumber(), s.getCreatedAt());
    }

    /** One-line address for the label: the richest parts we have, comma-joined, blanks skipped. */
    private String formatAddress(Address a) {
        if (a == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        appendPart(sb, a.getHouseFloor());
        appendPart(sb, a.getBuildingStreet());
        appendPart(sb, a.getAreaLocality());
        if (sb.length() == 0) {
            appendPart(sb, a.getLine1());
            appendPart(sb, a.getLine2());
        }
        appendPart(sb, a.getLandmark());
        appendPart(sb, a.getCity());
        appendPart(sb, a.getState());
        return sb.length() == 0 ? null : sb.toString();
    }

    private void appendPart(StringBuilder sb, String part) {
        if (part != null && !part.isBlank()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(part.trim());
        }
    }

    private MyShipmentDetailResponse toDetail(Shipment s) {
        Address o = s.getOriginAddress();
        Address d = s.getDestAddress();
        Optional<FlightTrackingPort.LivePosition> position = flightTrackingPort.currentPosition(s.getId());
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
                s.getCreatedAt(), s.getCancelledAt(),
                position.map(FlightTrackingPort.LivePosition::lat).orElse(null),
                position.map(FlightTrackingPort.LivePosition::lon).orElse(null),
                position.map(FlightTrackingPort.LivePosition::asOf).orElse(null),
                position.map(FlightTrackingPort.LivePosition::status).orElse(null),
                s.getFundingSource(), s.getTrackToken());
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
