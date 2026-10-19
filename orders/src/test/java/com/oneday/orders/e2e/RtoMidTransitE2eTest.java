package com.oneday.orders.e2e;

import com.oneday.common.domain.enums.PaymentMode;
import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.domain.Shipment;
import com.oneday.orders.service.ReturnService;
import com.oneday.orders.service.TransitionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Mid-transit RTO (feature iii) end-to-end under the R4 dock-receive gate: an in-custody cancel
 * becomes a return-to-sender. Covers the one <b>synchronous</b> resolution (already at the dest hub →
 * reverse-lane now) plus the deferred origin-hub/in-flight cases and the ops trigger. The same-city
 * (pre-hub) case resolves on an AFTER_COMMIT transition into the origin hub and so can't fire under the
 * {@code @Transactional} rollback base — it is unit-tested in {@code RtoIntentResolverTest} and proven
 * live e2e. Under R4 there is no bag-pull path.
 */
@DisplayName("E2E · RTO mid-transit")
class RtoMidTransitE2eTest extends OrdersE2eSupport {

    private static final String ADMIN = "ADMIN";

    @Autowired ReturnService returnService;

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
        assertThat(child.getParentShipmentId()).isEqualTo(idOf(ref));
    }

    // R4: once the parcel has been hub-scanned (origin hub onward) it is committed to fly — the RTO is
    // scheduled to fire reverse-lane at the destination hub, not resolved same-city now.
    @Test
    void originHubHubScanned_defersToDestHub() throws Exception {
        String token = tokenFor("B2C_CUSTOMER", randomUserId());
        String ref = bookB2c(token, PaymentMode.PREPAID);
        drive(ref, ShipmentState.PICKUP_ASSIGNED, ShipmentState.PICKED_UP, ShipmentState.HANDED_TO_PICKUP_VAN,
                ShipmentState.AT_ORIGIN_HUB, ShipmentState.ORIGIN_HUB_PROCESSING);

        mvc.perform(delete("/api/v1/b2c/shipments/{ref}", ref)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idemKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disposition").value("RETURN_SCHEDULED"));

        assertThat(shipmentRepository.findByShipmentRef(ref + "_R")).isEmpty(); // not minted yet — flies first
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

    // The reverse-lane return lands on the hub RTO worklist (R4: no bag-pull, so needs_bag_pull=false).
    @Test
    void returnAppearsOnHubWorklist() throws Exception {
        String token = tokenFor("B2C_CUSTOMER", randomUserId());
        String ref = bookB2c(token, PaymentMode.PREPAID);
        drive(ref, ShipmentState.PICKUP_ASSIGNED, ShipmentState.PICKED_UP, ShipmentState.HANDED_TO_PICKUP_VAN,
                ShipmentState.AT_ORIGIN_HUB, ShipmentState.ORIGIN_HUB_PROCESSING, ShipmentState.IN_TAKEOFF_BAG,
                ShipmentState.DISPATCHED_TO_AIRPORT, ShipmentState.AT_AIRPORT, ShipmentState.DEPARTED,
                ShipmentState.LANDED, ShipmentState.DISPATCHED_TO_HUB, ShipmentState.AT_DEST_HUB);
        mvc.perform(delete("/api/v1/b2c/shipments/{ref}", ref)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idemKey()))
                .andExpect(status().isOk());

        String adminToken = tokenFor(ADMIN, randomUserId());
        mvc.perform(get("/api/v1/admin/rto-worklist").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.child_ref=='" + ref + "_R')].needs_bag_pull").value(
                        org.hamcrest.Matchers.hasItem(false)));
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

    // ── One-scan RTO at the dock (the M7 ↔ M4 seam this fix wires) ─────────────────────────────────

    // ORIGIN hub, one scan: a pre-hub cancel leaves a deferred same-city intent (no child yet); the single
    // dock scan then resolves it — resolveOnHubArrival drives the AT_ORIGIN_HUB arrival AND mints the
    // same-city child (DEL→DEL) in one call, so M7 sorts it into a delivery bag without a second scan.
    @Test
    void resolveOnHubArrival_originHub_mintsSameCityChildInOneCall() throws Exception {
        String token = tokenFor("B2C_CUSTOMER", randomUserId());
        String ref = bookB2c(token, PaymentMode.PREPAID);
        drive(ref, ShipmentState.PICKUP_ASSIGNED, ShipmentState.PICKED_UP, ShipmentState.HANDED_TO_PICKUP_VAN);
        mvc.perform(delete("/api/v1/b2c/shipments/{ref}", ref)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idemKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disposition").value("RETURN_SCHEDULED"));
        assertThat(shipmentRepository.findByShipmentRef(ref + "_R")).isEmpty(); // not minted yet

        Optional<ReturnService.ReturnResult> result = returnService.resolveOnHubArrival(
                idOf(ref), ShipmentState.AT_ORIGIN_HUB, TransitionContext.fromSystem("e2e-onescan"));

        assertThat(result).isPresent();
        Shipment original = shipmentRepository.findByShipmentRef(ref).orElseThrow();
        assertThat(original.getState()).isEqualTo(ShipmentState.RTO_INITIATED);
        assertThat(original.getRtoResolvedAt()).isNotNull();
        Shipment child = shipmentRepository.findByShipmentRef(ref + "_R").orElseThrow();
        assertThat(child.getOriginCity()).isEqualTo("DEL"); // same-city: returns within the origin city
        assertThat(child.getDestCity()).isEqualTo("DEL");
        assertThat(child.getParentShipmentId()).isEqualTo(original.getId());
    }

    // DEST hub, one scan: a committed-transit cancel leaves a deferred reverse intent; the single dock scan
    // at the dest hub resolves it — resolveOnHubArrival mints the REVERSE child (BLR→DEL) so M7 sorts it
    // onto a flight back, NOT into a dest-city delivery bag (the dest-branch gap this fix closes).
    @Test
    void resolveOnHubArrival_destHub_mintsReverseChildInOneCall() throws Exception {
        String token = tokenFor("B2C_CUSTOMER", randomUserId());
        String ref = bookB2c(token, PaymentMode.PREPAID);
        drive(ref, ShipmentState.PICKUP_ASSIGNED, ShipmentState.PICKED_UP, ShipmentState.HANDED_TO_PICKUP_VAN,
                ShipmentState.AT_ORIGIN_HUB, ShipmentState.ORIGIN_HUB_PROCESSING, ShipmentState.IN_TAKEOFF_BAG,
                ShipmentState.DISPATCHED_TO_AIRPORT, ShipmentState.AT_AIRPORT, ShipmentState.DEPARTED,
                ShipmentState.LANDED, ShipmentState.DISPATCHED_TO_HUB);
        mvc.perform(delete("/api/v1/b2c/shipments/{ref}", ref)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idemKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disposition").value("RETURN_SCHEDULED"));
        assertThat(shipmentRepository.findByShipmentRef(ref + "_R")).isEmpty();

        Optional<ReturnService.ReturnResult> result = returnService.resolveOnHubArrival(
                idOf(ref), ShipmentState.AT_DEST_HUB, TransitionContext.fromSystem("e2e-onescan"));

        assertThat(result).isPresent();
        Shipment original = shipmentRepository.findByShipmentRef(ref).orElseThrow();
        assertThat(original.getState()).isEqualTo(ShipmentState.RTO_INITIATED);
        Shipment child = shipmentRepository.findByShipmentRef(ref + "_R").orElseThrow();
        assertThat(child.getOriginCity()).isEqualTo("BLR"); // reverse: fly back from the original dest
        assertThat(child.getDestCity()).isEqualTo("DEL");
        assertThat(child.getParentShipmentId()).isEqualTo(original.getId());
    }
}
