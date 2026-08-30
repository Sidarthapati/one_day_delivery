package com.oneday.orders.service.impl;

import com.oneday.common.domain.enums.CustomerType;
import com.oneday.orders.domain.ParcelOrder;
import com.oneday.orders.repository.ParcelOrderRepository;
import com.oneday.orders.service.OrderRefService;
import com.oneday.orders.service.OrderService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
class OrderServiceImpl implements OrderService {

    private final OrderRefService orderRefService;
    private final ParcelOrderRepository parcelOrderRepository;

    OrderServiceImpl(OrderRefService orderRefService, ParcelOrderRepository parcelOrderRepository) {
        this.orderRefService = orderRefService;
        this.parcelOrderRepository = parcelOrderRepository;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public CreatedOrder createOrder(CustomerType customerType, UUID b2bAccountId, String userId,
                                    String originCityCode, String purchaseOrderRef) {
        String city = originCityCode.toUpperCase();
        ParcelOrder order = new ParcelOrder();
        order.setOrderRef(orderRefService.generateRef(city));
        order.setCustomerType(customerType);
        order.setB2bAccountId(b2bAccountId);
        order.setBookedByUserId(UserIds.parse(userId));
        order.setPurchaseOrderRef(purchaseOrderRef);
        order.setParcelCount(0);
        order.setTotalPricePaise(0L);
        order.setCityId(city);
        order = parcelOrderRepository.save(order);
        return new CreatedOrder(order.getId(), order.getOrderRef());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void addShipment(UUID orderId, long shipmentTotalPaise) {
        parcelOrderRepository.addShipment(orderId, shipmentTotalPaise);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void removeShipment(UUID orderId, long shipmentTotalPaise) {
        parcelOrderRepository.removeShipment(orderId, shipmentTotalPaise);
    }
}
