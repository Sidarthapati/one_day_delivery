package com.oneday.orders.service.impl;

import com.oneday.common.domain.enums.CustomerType;
import com.oneday.orders.domain.ParcelOrder;
import com.oneday.orders.repository.ParcelOrderRepository;
import com.oneday.orders.service.OrderRefService;
import com.oneday.orders.service.OrderService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock private OrderRefService orderRefService;
    @Mock private ParcelOrderRepository parcelOrderRepository;

    @Test
    void createOrder_mintsRefAndPersistsEmptyOrder() {
        OrderServiceImpl service = new OrderServiceImpl(orderRefService, parcelOrderRepository);
        UUID accountId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(orderRefService.generateRef("BLR")).thenReturn("1DD-ORD-BLR-20260824-00001");
        when(parcelOrderRepository.save(any(ParcelOrder.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderService.CreatedOrder created = service.createOrder(
                CustomerType.B2B, accountId, userId.toString(), "blr", "PO-42");

        ArgumentCaptor<ParcelOrder> captor = ArgumentCaptor.forClass(ParcelOrder.class);
        verify(parcelOrderRepository).save(captor.capture());
        ParcelOrder saved = captor.getValue();
        assertThat(saved.getOrderRef()).isEqualTo("1DD-ORD-BLR-20260824-00001");
        assertThat(saved.getCustomerType()).isEqualTo(CustomerType.B2B);
        assertThat(saved.getB2bAccountId()).isEqualTo(accountId);
        assertThat(saved.getBookedByUserId()).isEqualTo(userId);
        assertThat(saved.getPurchaseOrderRef()).isEqualTo("PO-42");
        assertThat(saved.getParcelCount()).isZero();
        assertThat(saved.getTotalPricePaise()).isZero();
        assertThat(saved.getCityId()).isEqualTo("BLR");
        assertThat(created.orderRef()).isEqualTo("1DD-ORD-BLR-20260824-00001");
    }

    @Test
    void addShipment_delegatesAtomicIncrement() {
        OrderServiceImpl service = new OrderServiceImpl(orderRefService, parcelOrderRepository);
        UUID orderId = UUID.randomUUID();

        service.addShipment(orderId, 4720L);

        verify(parcelOrderRepository).addShipment(orderId, 4720L);
    }
}
