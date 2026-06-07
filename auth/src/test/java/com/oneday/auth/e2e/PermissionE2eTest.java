package com.oneday.auth.e2e;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("E2E · Permission checks")
class PermissionE2eTest extends AuthE2eSupport {

    // A privileged role (admin) checks another user's permission: a C2C customer HAS shipment:create.
    @Test
    void adminCheck_grantedPermission_isAllowed() throws Exception {
        String admin = adminToken();
        String email = uniqueEmail();
        createUser(admin, email, "C2C_CUSTOMER");

        mvc.perform(get("/permissions/check")
                        .param("email", email).param("action", "shipment:create")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true));
    }

    // The same customer does NOT have a staff permission (user:create) → allowed=false.
    @Test
    void adminCheck_ungrantedPermission_isDenied() throws Exception {
        String admin = adminToken();
        String email = uniqueEmail();
        createUser(admin, email, "C2C_CUSTOMER");

        mvc.perform(get("/permissions/check")
                        .param("email", email).param("action", "user:create")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false));
    }

    // A non-privileged user may only check their own permissions — checking someone else → 403.
    @Test
    void nonPrivilegedUser_checkingAnother_returns403() throws Exception {
        String custToken = tokenForRole("C2C_CUSTOMER");

        mvc.perform(get("/permissions/check")
                        .param("userId", UUID.randomUUID().toString()).param("action", "shipment:create")
                        .header("Authorization", bearer(custToken)))
                .andExpect(status().isForbidden());
    }
}
