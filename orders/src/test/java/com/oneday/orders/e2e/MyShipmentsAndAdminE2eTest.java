package com.oneday.orders.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.oneday.common.domain.enums.PaymentMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;

@DisplayName("E2E · My-shipments & admin orders view")
class MyShipmentsAndAdminE2eTest extends OrdersE2eSupport {

    // A customer's history endpoint returns exactly the shipments they booked (across sessions) —
    // here two of their own, each carrying the customer-visible state label, and none of another
    // customer's. This is the behaviour the refreshed "Your Bookings" list relies on.
    @Test
    void mine_returnsOnlyCallersBookings() throws Exception {
        String mine = randomUserId();
        String someoneElse = randomUserId();
        String myToken = tokenFor("B2C_CUSTOMER", mine);

        String refA = bookB2c(myToken, PaymentMode.COD);
        String refB = bookB2c(myToken, PaymentMode.COD);
        bookB2c(tokenFor("B2C_CUSTOMER", someoneElse), PaymentMode.COD);

        mvc.perform(get("/api/v1/shipments/mine")
                        .header("Authorization", "Bearer " + myToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].shipment_ref", containsInAnyOrder(refA, refB)))
                .andExpect(jsonPath("$[0].state_label").exists());
    }

    // A customer who has never booked sees an empty history (not an error).
    @Test
    void mine_emptyForNewCustomer() throws Exception {
        mvc.perform(get("/api/v1/shipments/mine")
                        .header("Authorization", "Bearer " + tokenFor("B2C_CUSTOMER", randomUserId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // The my-shipments view is for customers only: an ADMIN (who cannot book) is refused → 403.
    @Test
    void mine_forbiddenForAdmin() throws Exception {
        mvc.perform(get("/api/v1/shipments/mine")
                        .header("Authorization", "Bearer " + tokenFor("ADMIN", randomUserId())))
                .andExpect(status().isForbidden());
    }

    // An ADMIN browses the whole orders database across all cities.
    @Test
    void adminList_returnsAllCities() throws Exception {
        bookB2c(tokenFor("B2C_CUSTOMER", randomUserId()), PaymentMode.COD);

        mvc.perform(get("/api/v1/admin/shipments")
                        .header("Authorization", "Bearer " + tokenFor("ADMIN", randomUserId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_elements").value(greaterThanOrEqualTo(1)));
    }

    // A station manager sees only shipments touching their own city; every returned row is scoped
    // to that city (origin or destination).
    @Test
    void stationManager_scopedToOwnCity() throws Exception {
        // The default b2c route is DEL → BLR, so a DEL station manager must see it.
        bookB2c(tokenFor("B2C_CUSTOMER", randomUserId()), PaymentMode.COD);

        String body = mvc.perform(get("/api/v1/admin/shipments")
                        .header("Authorization", "Bearer "
                                + tokenForCity("STATION_MANAGER", randomUserId(), "DEL")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode rows = json.readTree(body).get("shipments");
        assertThat(rows).isNotEmpty();
        for (JsonNode row : rows) {
            assertThat("DEL".equals(row.get("origin_city").asText())
                    || "DEL".equals(row.get("dest_city").asText()))
                    .as("row %s must touch DEL", row.get("shipment_ref").asText())
                    .isTrue();
        }
    }

    // The orders database is read-only for non-privileged roles: a customer is refused → 403.
    @Test
    void adminList_forbiddenForCustomer() throws Exception {
        mvc.perform(get("/api/v1/admin/shipments")
                        .header("Authorization", "Bearer " + tokenFor("B2C_CUSTOMER", randomUserId())))
                .andExpect(status().isForbidden());
    }
}
