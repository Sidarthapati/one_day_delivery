package com.oneday.auth.e2e;

import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression for the path-based role rules added to {@code SecurityConfig} (Strix RC1 — Broken
 * Function-Level Authorization on the ops modules routing/hub/grid/airline/barcode). Those controllers
 * are not on the auth module's classpath, so authorization is what we exercise here: a DENIED request
 * is rejected with 403 at the authorization layer, while an ALLOWED request reaches the dispatcher and
 * 404s (no handler). That 403-vs-404 split is exactly the privilege boundary we are asserting.
 */
class SecurityConfigAuthzTest extends AuthE2eSupport {

    @Test
    void customerIsForbiddenFromOpsEndpoints() throws Exception {
        String cust = tokenForRole("B2C_CUSTOMER");
        forbidden(get("/hub/h1/bags"), cust);
        forbidden(get("/api/proposals"), cust);
        forbidden(get("/airline/hubs/DEL/awbs"), cust);
        forbidden(get("/routing/fleet/DEL"), cust);
        forbidden(post("/routing/plans/00000000-0000-0000-0000-000000000000/approve"), cust);
        forbidden(post("/api/v1/scan"), cust);
    }

    @Test
    void adminPassesAuthorizationOnOpsEndpoints() throws Exception {
        // Allowed by authz → reaches dispatch → 404 (no handler on the auth classpath), NOT 403.
        mvc.perform(get("/hub/h1/bags").header("Authorization", bearer(adminToken())))
                .andExpect(status().isNotFound());
    }

    @Test
    void serviceabilityStaysOpenToAnyAuthenticatedUser() throws Exception {
        // Carve-out: the booking-map serviceability lookup must NOT be ops-role-gated.
        mvc.perform(get("/api/grid/serviceable-at")
                        .header("Authorization", bearer(tokenForRole("B2C_CUSTOMER"))))
                .andExpect(status().isNotFound()); // allowed → no handler here → 404, not 403
    }

    private void forbidden(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b,
                           String token) throws Exception {
        mvc.perform(b.header("Authorization", bearer(token))).andExpect(status().isForbidden());
    }
}
