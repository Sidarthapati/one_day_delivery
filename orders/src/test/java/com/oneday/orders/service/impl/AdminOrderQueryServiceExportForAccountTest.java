package com.oneday.orders.service.impl;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.common.port.ShipmentScanTrailPort;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.dto.ShipmentSummaryResponse;
import com.oneday.orders.repository.ParcelOrderRepository;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.repository.ShipmentStateHistoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The merchant self-service export must read strictly the caller's own account — never a broad scan.
 * A regression that dropped the account filter (or keyed it on the wrong column) would leak other
 * merchants' shipments; this pins the query to the passed accountId and checks the row maps cleanly.
 */
class AdminOrderQueryServiceExportForAccountTest {

    private final ShipmentRepository shipmentRepo = mock(ShipmentRepository.class);
    private final ParcelOrderRepository orderRepo = mock(ParcelOrderRepository.class);
    private final ShipmentStateHistoryRepository historyRepo = mock(ShipmentStateHistoryRepository.class);
    private final ShipmentScanTrailPort scanTrail = mock(ShipmentScanTrailPort.class);

    private final AdminOrderQueryServiceImpl service =
            new AdminOrderQueryServiceImpl(shipmentRepo, orderRepo, historyRepo, scanTrail);

    @Test
    void exportsOnlyTheGivenAccountsShipments() {
        UUID accountId = UUID.randomUUID();
        Shipment s = new Shipment();
        s.setState(ShipmentState.BOOKED);
        s.setShipmentRef("1DD-SHP-DEL-20260826-00001");
        s.setOriginCity("DEL");
        s.setDestCity("BLR");
        when(shipmentRepo.findByB2bAccountId(eq(accountId), any(Pageable.class)))
                .thenReturn((Page<Shipment>) new PageImpl<>(List.of(s)));

        List<ShipmentSummaryResponse> rows = service.exportForAccount(accountId);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).shipmentRef()).isEqualTo("1DD-SHP-DEL-20260826-00001");
        // Fetched strictly by the caller's account — no cross-account scan.
        verify(shipmentRepo).findByB2bAccountId(eq(accountId), any(Pageable.class));
    }
}
