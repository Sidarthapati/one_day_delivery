package com.oneday.auth.e2e;

import com.oneday.auth.dto.request.CreateRoleRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("E2E · Role management")
class RoleE2eTest extends AuthE2eSupport {

    // An admin defines a new custom role with a permission set.
    @Test
    void adminCreatesRole() throws Exception {
        String name = "CUSTOM_" + System.nanoTime();
        mvc.perform(asJson(post("/roles").header("Authorization", bearer(adminToken())),
                        new CreateRoleRequest(name, "Custom Role", false, Set.of("pricing:quote"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(name));
    }

    // Listing roles returns the seeded role catalogue (e.g. ADMIN).
    @Test
    void listRoles_includesSeededRoles() throws Exception {
        mvc.perform(get("/roles").header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItem("ADMIN")));
    }

    // Role creation is admin-only — a station manager is refused → 403.
    @Test
    void nonAdminCannotCreateRole() throws Exception {
        mvc.perform(asJson(post("/roles").header("Authorization", bearer(tokenForRole("STATION_MANAGER"))),
                        new CreateRoleRequest("X_" + System.nanoTime(), "X", false, Set.of("pricing:quote"))))
                .andExpect(status().isForbidden());
    }

    // A freshly created, unused role can be deactivated by an admin.
    @Test
    void adminDeactivatesUnusedRole() throws Exception {
        String admin = adminToken();
        String name = "TEMP_" + System.nanoTime();
        String body = mvc.perform(asJson(post("/roles").header("Authorization", bearer(admin)),
                        new CreateRoleRequest(name, "Temp", false, Set.of("pricing:quote"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String roleId = json.readTree(body).get("id").asText();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/roles/{id}", roleId).header("Authorization", bearer(admin)))
                .andExpect(status().isNoContent());
    }
}
