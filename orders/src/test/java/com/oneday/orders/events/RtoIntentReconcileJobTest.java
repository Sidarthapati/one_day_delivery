package com.oneday.orders.events;

import com.oneday.common.domain.enums.ReturnReason;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.ReturnService;
import com.oneday.orders.service.ReturnService.ReturnLane;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The reconcile backstop re-resolves stranded RTO intents by the parcel's current hub state. */
class RtoIntentReconcileJobTest {

    private final ShipmentRepository shipmentRepo = mock(ShipmentRepository.class);
    private final ReturnService returnService = mock(ReturnService.class);
    private final RtoIntentReconcileJob job = new RtoIntentReconcileJob(shipmentRepo, returnService);

    private Shipment at(ShipmentState state) {
        Shipment s = new Shipment();
        ReflectionTestUtils.setField(s, "id", UUID.randomUUID());
        s.setShipmentRef("1DD-DEL-20260906-000" + state.ordinal());
        s.setState(state);
        return s;
    }

    private void stubReturn() {
        when(returnService.initiateReturn(any(), any(), any(), any()))
                .thenReturn(new ReturnService.ReturnResult(UUID.randomUUID(), "REF_R", UUID.randomUUID()));
    }

    @Test
    void resolvesEachByHubStateLane() {
        Shipment origin = at(ShipmentState.AT_ORIGIN_HUB);
        Shipment dest = at(ShipmentState.AT_DEST_HUB);
        when(shipmentRepo.findStrandedRtoIntents(any(), any(Pageable.class)))
                .thenReturn(List.of(origin, dest));
        stubReturn();

        job.reconcile();

        verify(returnService).initiateReturn(eq(origin.getId()), eq(ReturnReason.POST_CUSTODY_CANCEL),
                eq(ReturnLane.SAME_CITY_FROM_ORIGIN), any());
        verify(returnService).initiateReturn(eq(dest.getId()), eq(ReturnReason.POST_CUSTODY_CANCEL),
                eq(ReturnLane.REVERSE_FROM_DEST), any());
    }

    @Test
    void nothingStrandedIsNoOp() {
        when(shipmentRepo.findStrandedRtoIntents(any(), any(Pageable.class))).thenReturn(List.of());

        job.reconcile();

        verify(returnService, org.mockito.Mockito.never())
                .initiateReturn(any(), any(), any(), anyBoolean(), any());
        verify(returnService, org.mockito.Mockito.never())
                .initiateReturn(any(), any(), any(), any());
    }

    @Test
    void oneFailureDoesNotAbortTheRest() {
        Shipment a = at(ShipmentState.AT_DEST_HUB);
        Shipment b = at(ShipmentState.AT_DEST_HUB);
        when(shipmentRepo.findStrandedRtoIntents(any(), any(Pageable.class))).thenReturn(List.of(a, b));
        when(returnService.initiateReturn(eq(a.getId()), any(), any(), any()))
                .thenThrow(new RuntimeException("pricing port down"));
        when(returnService.initiateReturn(eq(b.getId()), any(), any(), any()))
                .thenReturn(new ReturnService.ReturnResult(UUID.randomUUID(), "REF_R", UUID.randomUUID()));

        job.reconcile();

        // b still resolved despite a throwing.
        verify(returnService, times(1)).initiateReturn(eq(b.getId()), any(), any(), any());
    }
}
