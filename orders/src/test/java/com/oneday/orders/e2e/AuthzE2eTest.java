package com.oneday.orders.e2e;

import com.oneday.common.domain.enums.PaymentMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("E2E · Authorization gates")
class AuthzE2eTest extends OrdersE2eSupport {

    // No bearer token → the real JWT filter leaves the request unauthenticated → 401.
    @Test
    void bookingWithoutToken_returns401() throws Exception {
        mvc.perform(post("/api/v1/b2c/shipments")
                        .header("Idempotency-Key", idemKey())
                        .contentType("application/json")
                        .content(json.writeValueAsString(b2cRequest(PaymentMode.COD))))
                .andExpect(status().isUnauthorized());
    }

    // Booking is reserved for customer accounts: an ADMIN is explicitly denied (no ADMIN bypass on
    // the booking gate) — admins read the orders DB but cannot place orders → 403.
    @Test
    void adminCannotBook_returns403() throws Exception {
        mvc.perform(post("/api/v1/b2c/shipments")
                        .header("Authorization", "Bearer " + tokenFor("ADMIN", randomUserId()))
                        .header("Idempotency-Key", idemKey())
                        .contentType("application/json")
                        .content(json.writeValueAsString(b2cRequest(PaymentMode.COD))))
                .andExpect(status().isForbidden());
    }

    // An operational role (delivery associate) is not a customer and cannot book → 403.
    @Test
    void deliveryAssociateCannotBook_returns403() throws Exception {
        mvc.perform(post("/api/v1/b2c/shipments")
                        .header("Authorization", "Bearer " + tokenFor("DELIVERY_ASSOCIATE", randomUserId()))
                        .header("Idempotency-Key", idemKey())
                        .contentType("application/json")
                        .content(json.writeValueAsString(b2cRequest(PaymentMode.COD))))
                .andExpect(status().isForbidden());
    }
}
