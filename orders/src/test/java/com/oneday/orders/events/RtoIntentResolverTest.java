package com.oneday.orders.events;

import com.oneday.common.domain.enums.ReturnReason;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.repository.ShipmentRepository;
import com.oneday.orders.service.ReturnService;
import com.oneday.orders.service.ReturnService.ReturnLane;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A deferred mid-transit RTO intent fires the right lane when the parcel reaches a hub. */
class RtoIntentResolverTest {

    private final ShipmentRepository shipmentRepo = mock(ShipmentRepository.class);
    private final ReturnService returnService = mock(ReturnService.class);
    private final RtoIntentResolver resolver = new RtoIntentResolver(shipmentRepo, returnService);

    private final UUID shipmentId = UUID.randomUUID();

    private ShipmentTransitioned arrivedAt(ShipmentState hub) {
        return new ShipmentTransitioned(shipmentId, "1DD-DEL-20260906-00001",
                hub == ShipmentState.AT_ORIGIN_HUB ? ShipmentState.HANDED_TO_PICKUP_VAN : ShipmentState.DISPATCHED_TO_HUB,
                hub, "sys", null, null);
    }

    private Shipment pendingIntent() {
        Shipment s = new Shipment();
        ReflectionTestUtils.setField(s, "id", shipmentId);
        s.setShipmentRef("1DD-DEL-20260906-00001");
        s.setRtoRequestedAt(Instant.now());
        return s;
    }

    private void stubReturn() {
        when(returnService.initiateReturn(any(), any(), any(), any()))
                .thenReturn(new ReturnService.ReturnResult(UUID.randomUUID(), "1DD-DEL-20260906-00001_R", shipmentId));
    }

    @Test
    void originHubArrivalFiresSameCityReturn() {
        Shipment s = pendingIntent();
        when(shipmentRepo.findById(shipmentId)).thenReturn(Optional.of(s));
        stubReturn();

        resolver.onShipmentTransitioned(arrivedAt(ShipmentState.AT_ORIGIN_HUB));

        verify(returnService).initiateReturn(eq(shipmentId), eq(ReturnReason.POST_CUSTODY_CANCEL),
                eq(ReturnLane.SAME_CITY_FROM_ORIGIN), any());
        // The resolver delegates to initiateReturn, which stamps rto_resolved_at on the row it locks and
        // transitions — the resolver must NOT re-save its own (different persistence-context) instance
        // `s`, or it would clobber the RTO_INITIATED transition (open-in-view is off). So `s` is untouched
        // here and never saved; the resolved-stamp is covered by ReturnServiceImplTest.
        verify(shipmentRepo, never()).save(any());
    }

    @Test
    void destHubArrivalFiresReverseLaneReturn() {
        when(shipmentRepo.findById(shipmentId)).thenReturn(Optional.of(pendingIntent()));
        stubReturn();

        resolver.onShipmentTransitioned(arrivedAt(ShipmentState.AT_DEST_HUB));

        verify(returnService).initiateReturn(eq(shipmentId), eq(ReturnReason.POST_CUSTODY_CANCEL),
                eq(ReturnLane.REVERSE_FROM_DEST), any());
    }

    @Test
    void noIntentIsIgnored() {
        Shipment s = pendingIntent();
        s.setRtoRequestedAt(null); // no pending intent
        when(shipmentRepo.findById(shipmentId)).thenReturn(Optional.of(s));

        resolver.onShipmentTransitioned(arrivedAt(ShipmentState.AT_DEST_HUB));

        verify(returnService, never()).initiateReturn(any(), any(), any(), any());
    }

    @Test
    void alreadyResolvedIntentIsIgnored() {
        Shipment s = pendingIntent();
        s.setRtoResolvedAt(Instant.now());
        when(shipmentRepo.findById(shipmentId)).thenReturn(Optional.of(s));

        resolver.onShipmentTransitioned(arrivedAt(ShipmentState.AT_DEST_HUB));

        verify(returnService, never()).initiateReturn(any(), any(), any(), any());
    }

    @Test
    void returnChildIsNeverReturnedAgain() {
        Shipment child = pendingIntent();
        child.setReturnOfShipmentId(UUID.randomUUID()); // this shipment is itself a return child
        when(shipmentRepo.findById(shipmentId)).thenReturn(Optional.of(child));

        resolver.onShipmentTransitioned(arrivedAt(ShipmentState.AT_DEST_HUB));

        verify(returnService, never()).initiateReturn(any(), any(), any(), any());
    }

    @Test
    void reverseIntentReachingOriginHubIsSkipped() {
        // A committed intent (cancel after the hub scan) carries REVERSE_FROM_DEST — it must fly and
        // resolve at the dest hub, NOT be turned around same-city if it passes back through the origin hub.
        Shipment s = pendingIntent();
        s.setRtoLane(ReturnLane.REVERSE_FROM_DEST.name());
        when(shipmentRepo.findById(shipmentId)).thenReturn(Optional.of(s));

        resolver.onShipmentTransitioned(arrivedAt(ShipmentState.AT_ORIGIN_HUB));

        verify(returnService, never()).initiateReturn(any(), any(), any(), any());
    }

    @Test
    void nonHubTransitionIsIgnored() {
        resolver.onShipmentTransitioned(new ShipmentTransitioned(shipmentId, "ref",
                ShipmentState.DEPARTED, ShipmentState.LANDED, "sys", null, null));

        verify(returnService, never()).initiateReturn(any(), any(), any(), any());
    }
}
