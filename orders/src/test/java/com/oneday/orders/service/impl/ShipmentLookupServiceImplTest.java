package com.oneday.orders.service.impl;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.dto.BarcodeResolution;
import com.oneday.orders.repository.ParcelOrderRepository;
import com.oneday.orders.repository.ShipmentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** R1: a scanned barcode resolves to the active shipment — the live return child once RTO is on. */
class ShipmentLookupServiceImplTest {

    private final ShipmentRepository shipmentRepo = mock(ShipmentRepository.class);
    private final ParcelOrderRepository orderRepo = mock(ParcelOrderRepository.class);
    private final ShipmentLookupServiceImpl service = new ShipmentLookupServiceImpl(shipmentRepo, orderRepo);

    private static final String BARCODE = "1DD-BLR-20260906-000042";

    private Shipment shipment(String ref, ShipmentState state, UUID returnOf) {
        Shipment s = new Shipment();
        ReflectionTestUtils.setField(s, "id", UUID.randomUUID());
        s.setShipmentRef(ref);
        s.setParcelId(BARCODE);
        s.setState(state);
        s.setReturnOfShipmentId(returnOf);
        return s;
    }

    @Test
    void unknownBarcodeIsEmpty() {
        when(shipmentRepo.findByParcelId(BARCODE)).thenReturn(List.of());

        assertThat(service.findActiveByParcelId(BARCODE)).isEmpty();
    }

    @Test
    void originalOnlyResolvesToOriginal() {
        Shipment original = shipment("1DD-DEL-20260906-000007", ShipmentState.AT_ORIGIN_HUB, null);
        when(shipmentRepo.findByParcelId(BARCODE)).thenReturn(List.of(original));

        BarcodeResolution r = service.findActiveByParcelId(BARCODE).orElseThrow();

        assertThat(r.isReturn()).isFalse();
        assertThat(r.shipmentId()).isEqualTo(original.getId());
        assertThat(r.shipmentRef()).isEqualTo("1DD-DEL-20260906-000007");
        assertThat(r.originalRef()).isNull();
    }

    @Test
    void scanningOriginalRefRoutesToReturnChild() {
        // The return reuses the original's label; scanning it yields the ORIGINAL ref. With a live
        // return child, the scan must route to that child (<ref>_R), not the original.
        Shipment original = shipment("1DD-DEL-20260906-000007", ShipmentState.RTO_INITIATED, null);
        Shipment child = shipment("1DD-DEL-20260906-000007_R", ShipmentState.AT_ORIGIN_HUB, original.getId());
        original.setReturnShipmentId(child.getId());
        when(shipmentRepo.findByShipmentRef("1DD-DEL-20260906-000007")).thenReturn(Optional.of(original));
        when(shipmentRepo.findById(child.getId())).thenReturn(Optional.of(child));

        BarcodeResolution r = service.findActiveByParcelId("1DD-DEL-20260906-000007").orElseThrow();

        assertThat(r.isReturn()).isTrue();
        assertThat(r.shipmentRef()).isEqualTo("1DD-DEL-20260906-000007_R");
        assertThat(r.originalRef()).isEqualTo("1DD-DEL-20260906-000007");
    }

    @Test
    void whenReturnChildExistsItWins() {
        Shipment original = shipment("1DD-DEL-20260906-000007", ShipmentState.RTO_INITIATED, null);
        Shipment child = shipment("1DD-DEL-20260906-000007_R", ShipmentState.AT_ORIGIN_HUB, original.getId());
        // Repo may return in either order — resolver must still prefer the child.
        when(shipmentRepo.findByParcelId(BARCODE)).thenReturn(List.of(original, child));

        BarcodeResolution r = service.findActiveByParcelId(BARCODE).orElseThrow();

        assertThat(r.isReturn()).isTrue();
        assertThat(r.shipmentId()).isEqualTo(child.getId());
        assertThat(r.shipmentRef()).isEqualTo("1DD-DEL-20260906-000007_R");
        assertThat(r.originalRef()).isEqualTo("1DD-DEL-20260906-000007");
    }
}
