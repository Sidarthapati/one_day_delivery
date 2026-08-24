package com.oneday.orders.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.oneday.common.domain.enums.PaymentMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage for the Order → N Shipments abstraction: a real HTTP booking mints a parent
 * order, and both the customer and admin order views group the parcels under it. Full stack —
 * JWT filter → controller → service → real Postgres — with only cross-module ports mocked.
 */
class OrderGroupingE2eTest extends OrdersE2eSupport {

    @Test
    void b2cBooking_mintsOrder_visibleInCustomerAndAdminViews() throws Exception {
        String userId = randomUserId();
        String customer = tokenFor("B2C_CUSTOMER", userId);

        // 1. Book a single B2C shipment — the response now echoes the parent order ref.
        String bookBody = mvc.perform(post("/api/v1/b2c/shipments")
                        .header("Authorization", "Bearer " + customer)
                        .header("Idempotency-Key", idemKey())
                        .contentType("application/json")
                        .content(json.writeValueAsString(b2cRequest(PaymentMode.PREPAID))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order_ref").exists())
                .andReturn().getResponse().getContentAsString();
        JsonNode booked = json.readTree(bookBody);
        String orderRef = booked.get("order_ref").asText();
        String shipmentRef = booked.get("shipment_ref").asText();
        assertThat(orderRef).startsWith("1DD-ORD-");

        // 2. Customer "my orders" — one order of one parcel.
        mvc.perform(get("/api/v1/orders/mine").header("Authorization", "Bearer " + customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.order_ref=='" + orderRef + "')]").exists())
                .andExpect(jsonPath("$[?(@.order_ref=='" + orderRef + "')].parcel_count").value(1));

        // 3. Customer order detail — expands to the child shipment.
        mvc.perform(get("/api/v1/orders/mine/" + orderRef).header("Authorization", "Bearer " + customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.order_ref").value(orderRef))
                .andExpect(jsonPath("$.order.parcel_count").value(1))
                .andExpect(jsonPath("$.shipments[0].shipment_ref").value(shipmentRef));

        // 4. Another customer cannot see this order (owner-scoped → 404).
        String other = tokenFor("B2C_CUSTOMER", randomUserId());
        mvc.perform(get("/api/v1/orders/mine/" + orderRef).header("Authorization", "Bearer " + other))
                .andExpect(status().isNotFound());

        // 5. Business/admin console — the order and its parcel appear.
        String admin = tokenFor("ADMIN", randomUserId());
        mvc.perform(get("/api/v1/admin/orders").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders[?(@.order_ref=='" + orderRef + "')]").exists());
        mvc.perform(get("/api/v1/admin/orders/" + orderRef).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.order_ref").value(orderRef))
                .andExpect(jsonPath("$.shipments[0].shipment_ref").value(shipmentRef));
    }

    @Test
    void b2bBooking_persistsPurchaseOrderRefOnOrder() throws Exception {
        String owner = tokenFor("B2B_USER", B2B_OWNER_USER_ID);
        var req = b2bRequest();
        req.setPurchaseOrderRef("PO-E2E-001");

        String bookBody = mvc.perform(post("/api/v1/b2b/shipments")
                        .header("Authorization", "Bearer " + owner)
                        .header("Idempotency-Key", idemKey())
                        .contentType("application/json")
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.order_ref").exists())
                .andReturn().getResponse().getContentAsString();
        String orderRef = json.readTree(bookBody).get("order_ref").asText();

        // The merchant's PO ref — previously dropped — now rides on the parent order.
        mvc.perform(get("/api/v1/orders/mine/" + orderRef).header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.purchase_order_ref").value("PO-E2E-001"))
                .andExpect(jsonPath("$.order.parcel_count").value(1));
    }
}
