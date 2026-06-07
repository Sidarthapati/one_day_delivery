package com.oneday.auth.e2e;

import com.oneday.auth.dto.request.ChangePasswordRequest;
import com.oneday.auth.dto.request.LoginRequest;
import com.oneday.auth.dto.request.RegisterUserRequest;
import com.oneday.auth.dto.request.ResetPasswordRequest;
import com.oneday.auth.dto.request.RoleChangeRequest;
import com.oneday.auth.dto.request.UpdateProfileRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("E2E · User management")
class UserManagementE2eTest extends AuthE2eSupport {

    // An admin provisions a staff user with an explicit role.
    @Test
    void adminCreatesUser_withRole() throws Exception {
        String email = uniqueEmail();
        mvc.perform(asJson(post("/users").header("Authorization", bearer(adminToken())),
                        new RegisterUserRequest("Hub Lead", email, PW, "STATION_MANAGER", "DEL")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("STATION_MANAGER"));
    }

    // A station manager may also create users (delegated provisioning within their remit).
    @Test
    void stationManagerCreatesUser() throws Exception {
        String smToken = tokenForRole("STATION_MANAGER");
        mvc.perform(asJson(post("/users").header("Authorization", bearer(smToken)),
                        new RegisterUserRequest("DA One", uniqueEmail(), PW, "DELIVERY_ASSOCIATE", "DEL")))
                .andExpect(status().isOk());
    }

    // A customer has no user-provisioning rights → 403.
    @Test
    void customerCannotCreateUser() throws Exception {
        String custToken = tokenForRole("C2C_CUSTOMER");
        mvc.perform(asJson(post("/users").header("Authorization", bearer(custToken)),
                        new RegisterUserRequest("X", uniqueEmail(), PW, "DELIVERY_ASSOCIATE", null)))
                .andExpect(status().isForbidden());
    }

    // Creating a user with an email that already exists is a 409 conflict.
    @Test
    void createUser_duplicateEmail_returns409() throws Exception {
        String admin = adminToken();
        String email = uniqueEmail();
        createUser(admin, email, "C2C_CUSTOMER");
        mvc.perform(asJson(post("/users").header("Authorization", bearer(admin)),
                        new RegisterUserRequest("Dup", email, PW, "C2C_CUSTOMER", null)))
                .andExpect(status().isConflict());
    }

    // An admin changes a user's role; the change is recorded in the append-only audit log.
    @Test
    void changeRole_isAuditLogged() throws Exception {
        String admin = adminToken();
        String userId = createUserReturningId(admin, uniqueEmail(), "C2C_CUSTOMER");
        // Change to another non-city-scoped role so the change isn't blocked by the city rule.
        UUID b2bRole = roleId(admin, "B2B_USER");

        mvc.perform(asJson(put("/users/{id}/role", userId).header("Authorization", bearer(admin)),
                        new RoleChangeRequest(b2bRole, "promotion")))
                .andExpect(status().isNoContent());

        mvc.perform(get("/users/{id}/audit-log", userId).header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(greaterThanOrEqualTo(1)));
    }

    // A deactivated user can be reactivated and then log in again.
    @Test
    void deactivateThenReactivate_restoresLogin() throws Exception {
        String admin = adminToken();
        String email = uniqueEmail();
        String userId = createUserReturningId(admin, email, "C2C_CUSTOMER");

        mvc.perform(delete("/users/{id}", userId).header("Authorization", bearer(admin)))
                .andExpect(status().isNoContent());
        mvc.perform(put("/users/{id}/reactivate", userId).header("Authorization", bearer(admin)))
                .andExpect(status().isNoContent());

        mvc.perform(asJson(post("/auth/login"), new LoginRequest(email, PW)))
                .andExpect(status().isOk());
    }

    // An admin resets a user's password; the user logs in with the new password and is flagged to
    // change it.
    @Test
    void adminResetPassword_setsNewPasswordAndForcesChange() throws Exception {
        String admin = adminToken();
        String email = uniqueEmail();
        String userId = createUserReturningId(admin, email, "C2C_CUSTOMER");

        mvc.perform(asJson(post("/users/{id}/reset-password", userId).header("Authorization", bearer(admin)),
                        new ResetPasswordRequest("ResetPass1!")))
                .andExpect(status().isNoContent());

        mvc.perform(asJson(post("/auth/login"), new LoginRequest(email, "ResetPass1!")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true));
    }

    // A user changes their own password: the new one works, the old one no longer does.
    @Test
    void changeOwnPassword_rotatesCredential() throws Exception {
        String email = uniqueEmail();
        createUser(adminToken(), email, "C2C_CUSTOMER");
        String token = login(email, PW);

        mvc.perform(asJson(put("/users/me/password").header("Authorization", bearer(token)),
                        new ChangePasswordRequest(PW, "BrandNew1!")))
                .andExpect(status().isNoContent());

        mvc.perform(asJson(post("/auth/login"), new LoginRequest(email, "BrandNew1!")))
                .andExpect(status().isOk());
        mvc.perform(asJson(post("/auth/login"), new LoginRequest(email, PW)))
                .andExpect(status().isUnauthorized());
    }

    // A user updates their own display name; the change is reflected when the record is read back.
    @Test
    void updateOwnProfile_changesName() throws Exception {
        String admin = adminToken();
        String email = uniqueEmail();
        String userId = createUserReturningId(admin, email, "C2C_CUSTOMER");
        String token = login(email, PW);

        mvc.perform(asJson(put("/users/me").header("Authorization", bearer(token)),
                        new UpdateProfileRequest("Renamed User")))
                .andExpect(status().isNoContent());

        mvc.perform(get("/users/{id}", userId).header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed User"));
    }

    // Looking up a user by email is restricted to privileged roles: admin succeeds, a customer 403s.
    @Test
    void getUserByEmail_isPrivileged() throws Exception {
        String admin = adminToken();
        String email = uniqueEmail();
        createUser(admin, email, "C2C_CUSTOMER");

        mvc.perform(get("/users").param("email", email).header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));

        mvc.perform(get("/users").param("email", email)
                        .header("Authorization", bearer(tokenForRole("C2C_CUSTOMER"))))
                .andExpect(status().isForbidden());
    }

    // ── helper ──
    private String createUserReturningId(String adminToken, String email, String role) throws Exception {
        String body = mvc.perform(asJson(post("/users").header("Authorization", bearer(adminToken)),
                        new RegisterUserRequest("Test " + role, email, PW, role, cityFor(role))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asText();
    }
}
