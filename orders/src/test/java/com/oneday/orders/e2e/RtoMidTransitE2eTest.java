package com.oneday.orders.e2e;

import com.oneday.common.domain.enums.PaymentMode;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.common.port.HubRecallPort;
import com.oneday.orders.domain.Shipment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Mid-transit RTO (feature iii) end-to-end: an in-custody cancel becomes a return-to-sender. Covers
 * the <b>synchronous</b> resolutions (already at a hub / pulled from an OPEN origin bag) plus the ops
 * trigger; the deferred resolver is an AFTER_COMMIT listener and can't fire under the @Transactional
 * rollback base, so it is unit-tested in {@code RtoIntentResolverTest}.
 */
@DisplayName("E2E · RTO mid-transit")
class RtoMidTransitE2eTest extends OrdersE2eSupport {

    private static final String ADMIN = "ADMIN";

    // At the destination hub, a cancel spawns a reverse-lane return child now and moves the original
    // to RTO_INITIATED — no refund.
    @Test
    void atDestHub_cancelReturnsReverseLaneNow() throws Exception {
        String token = tokenFor("B2C_CUSTOMER", randomUserId());
        String ref = bookB2c(token, PaymentMode.PREPAID);
        drive(ref, ShipmentState.PICKUP_ASSIGNED, ShipmentState.PICKED_UP, ShipmentState.HANDED_TO_PICKUP_VAN,
                ShipmentState.AT_ORIGIN_HUB, ShipmentState.ORIGIN_HUB_PROCESSING, ShipmentState.IN_TAKEOFF_BAG,
                ShipmentState.DISPATCHED_TO_AIRPORT, ShipmentState.AT_AIRPORT, ShipmentState.DEPARTED,
                ShipmentState.LANDED, ShipmentState.DISPATCHED_TO_HUB, ShipmentState.AT_DEST_HUB);

        mvc.perform(delete("/api/v1/b2c/shipments/{ref}", ref)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idemKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RTO_INITIATED"))
                .andExpect(jsonPath("$.disposition").value("RETURN_INITIATED"))
                .andExpect(jsonPath("$.return_child_ref").value(ref + "_R"));

        // The freshly-minted child row proves the reverse lane: origin = original dest (BLR),
        // delivered back to the original sender (DEL), linked to the original.
        Shipment child = shipmentRepository.findByShipmentRef(ref + "_R").orElseThrow();
        assertThat(child.getOriginCity()).isEqualTo("BLR");
        assertThat(child.getDestCity()).isEqualTo("DEL");
        assertThat(child.getState()).isEqualTo(ShipmentState.AT_ORIGIN_HUB);
        assertThat(child.getReturnOfShipmentId()).isEqualTo(idOf(ref));
    }

    // At the origin hub with an OPEN flight bag, the parcel is recalled and returned within the origin
    // city (same-city) — no flight.
    @Test
    void originHubOpenBag_recalledAndReturnedSameCity() throws Exception {
        when(hubRecallPort.recallAtOrigin(any())).thenReturn(HubRecallPort.RecallOutcome.PULLED_FROM_BAG);
        String token = tokenFor("B2C_CUSTOMER", randomUserId());
        String ref = bookB2c(token, PaymentMode.PREPAID);
        drive(ref, ShipmentState.PICKUP_ASSIGNED, ShipmentState.PICKED_UP, ShipmentState.HANDED_TO_PICKUP_VAN,
                ShipmentState.AT_ORIGIN_HUB, ShipmentState.ORIGIN_HUB_PROCESSING, ShipmentState.IN_TAKEOFF_BAG);

        mvc.perform(delete("/api/v1/b2c/shipments/{ref}", ref)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idemKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disposition").value("RETURN_INITIATED"));

        Shipment child = shipmentRepository.findByShipmentRef(ref + "_R").orElseThrow();
        // Same-city: both ends in the origin city (DEL); no flight.
        assertThat(child.getOriginCity()).isEqualTo("DEL");
        assertThat(child.getDestCity()).isEqualTo("DEL");
    }

    // At the origin hub with a SEALED bag, the parcel is committed to fly — the RTO is scheduled to
    // fire at the destination hub, not resolved now.
    @Test
    void originHubSealedBag_defersToDestHub() throws Exception {
        when(hubRecallPort.recallAtOrigin(any())).thenReturn(HubRecallPort.RecallOutcome.COMMITTED);
        String token = tokenFor("B2C_CUSTOMER", randomUserId());
        String ref = bookB2c(token, PaymentMode.PREPAID);
        drive(ref, ShipmentState.PICKUP_ASSIGNED, ShipmentState.PICKED_UP, ShipmentState.HANDED_TO_PICKUP_VAN,
                ShipmentState.AT_ORIGIN_HUB, ShipmentState.ORIGIN_HUB_PROCESSING, ShipmentState.IN_TAKEOFF_BAG);

        mvc.perform(delete("/api/v1/b2c/shipments/{ref}", ref)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idemKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disposition").value("RETURN_SCHEDULED"));

        assertThat(shipmentRepository.findByShipmentRef(ref + "_R")).isEmpty(); // not minted yet
    }

    // Ops trigger: an ADMIN initiates RTO on an in-custody shipment at the dest hub.
    @Test
    void opsAdminInitiatesRtoAtDestHub() throws Exception {
        String bookToken = tokenFor("B2C_CUSTOMER", randomUserId());
        String ref = bookB2c(bookToken, PaymentMode.PREPAID);
        drive(ref, ShipmentState.PICKUP_ASSIGNED, ShipmentState.PICKED_UP, ShipmentState.HANDED_TO_PICKUP_VAN,
                ShipmentState.AT_ORIGIN_HUB, ShipmentState.ORIGIN_HUB_PROCESSING, ShipmentState.IN_TAKEOFF_BAG,
                ShipmentState.DISPATCHED_TO_AIRPORT, ShipmentState.AT_AIRPORT, ShipmentState.DEPARTED,
                ShipmentState.LANDED, ShipmentState.DISPATCHED_TO_HUB, ShipmentState.AT_DEST_HUB);

        String adminToken = tokenFor(ADMIN, randomUserId());
        mvc.perform(post("/api/v1/admin/shipments/{ref}/rto", ref)
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", idemKey())
                        .param("reason", "merchant cancelled the order"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disposition").value("RETURN_INITIATED"));
    }

    // The return lands on the hub RTO worklist: an origin-hub OPEN-bag recall flags a bag-pull.
    @Test
    void returnAppearsOnHubWorklistWithBagPullFlag() throws Exception {
        when(hubRecallPort.recallAtOrigin(any())).thenReturn(HubRecallPort.RecallOutcome.PULLED_FROM_BAG);
        String token = tokenFor("B2C_CUSTOMER", randomUserId());
        String ref = bookB2c(token, PaymentMode.PREPAID);
        drive(ref, ShipmentState.PICKUP_ASSIGNED, ShipmentState.PICKED_UP, ShipmentState.HANDED_TO_PICKUP_VAN,
                ShipmentState.AT_ORIGIN_HUB, ShipmentState.ORIGIN_HUB_PROCESSING, ShipmentState.IN_TAKEOFF_BAG);
        mvc.perform(delete("/api/v1/b2c/shipments/{ref}", ref)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idemKey()))
                .andExpect(status().isOk());

        String adminToken = tokenFor(ADMIN, randomUserId());
        mvc.perform(get("/api/v1/admin/rto-worklist").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.child_ref=='" + ref + "_R')].needs_bag_pull").value(
                        org.hamcrest.Matchers.hasItem(true)));
    }

    // Ops RTO on a not-yet-in-custody shipment (nothing physical to return) is refused with 409.
    @Test
    void opsRtoBeforeCustody_returns409() throws Exception {
        String bookToken = tokenFor("B2C_CUSTOMER", randomUserId());
        String ref = bookB2c(bookToken, PaymentMode.PREPAID); // still BOOKED

        String adminToken = tokenFor(ADMIN, randomUserId());
        mvc.perform(post("/api/v1/admin/shipments/{ref}/rto", ref)
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Idempotency-Key", idemKey())
                        .param("reason", "too early"))
                .andExpect(status().isConflict());
    }
}
