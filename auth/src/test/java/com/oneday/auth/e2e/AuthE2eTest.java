package com.oneday.auth.e2e;

import com.oneday.auth.dto.request.LoginRequest;
import com.oneday.auth.dto.request.RegisterRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("E2E · Authentication & JWT")
class AuthE2eTest extends AuthE2eSupport {

    // The seeded admin logs in and receives a signed JWT plus their role.
    @Test
    void login_validCredentials_returnsTokenAndRole() throws Exception {
        mvc.perform(asJson(post("/auth/login"), new LoginRequest(ADMIN_EMAIL, ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.mustChangePassword").value(false));
    }

    // A wrong password is rejected with 401 (no token leaked).
    @Test
    void login_wrongPassword_returns401() throws Exception {
        mvc.perform(asJson(post("/auth/login"), new LoginRequest(ADMIN_EMAIL, "wrong-password")))
                .andExpect(status().isUnauthorized());
    }

    // An unknown email is rejected with 401 — indistinguishable from a wrong password.
    @Test
    void login_unknownEmail_returns401() throws Exception {
        mvc.perform(asJson(post("/auth/login"), new LoginRequest(uniqueEmail(), PW)))
                .andExpect(status().isUnauthorized());
    }

    // Self-service registration creates a C2C customer who can then log in with those credentials.
    @Test
    void register_thenLogin_succeedsAsC2cCustomer() throws Exception {
        String email = uniqueEmail();
        mvc.perform(asJson(post("/auth/register"), new RegisterRequest(email, PW, "New Customer", "+919000000001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("C2C_CUSTOMER"))
                .andExpect(jsonPath("$.phone").value("+919000000001"))
                .andExpect(jsonPath("$.token").isNotEmpty());

        mvc.perform(asJson(post("/auth/login"), new LoginRequest(email, PW)))
                .andExpect(status().isOk());
    }

    // Registering an email that already exists is a 409 conflict.
    @Test
    void register_duplicateEmail_returns409() throws Exception {
        String email = uniqueEmail();
        mvc.perform(asJson(post("/auth/register"), new RegisterRequest(email, PW, "First", "+919000000001")))
                .andExpect(status().isOk());
        mvc.perform(asJson(post("/auth/register"), new RegisterRequest(email, PW, "Second", "+919000000002")))
                .andExpect(status().isConflict());
    }

    // A deactivated user can no longer authenticate → 401.
    @Test
    void login_deactivatedUser_returns401() throws Exception {
        String admin = adminToken();
        String email = uniqueEmail();
        String body = mvc.perform(asJson(post("/users").header("Authorization", bearer(admin)),
                        new com.oneday.auth.dto.request.RegisterUserRequest("Temp", email, PW, "C2C_CUSTOMER", null)))
                .andReturn().getResponse().getContentAsString();
        String userId = json.readTree(body).get("id").asText();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/users/{id}", userId).header("Authorization", bearer(admin)))
                .andExpect(status().isNoContent());

        mvc.perform(asJson(post("/auth/login"), new LoginRequest(email, PW)))
                .andExpect(status().isUnauthorized());
    }

    // The health endpoint is public (no token needed).
    @Test
    void health_isPublic() throws Exception {
        mvc.perform(get("/auth/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    // A protected endpoint with no Authorization header is rejected by the security chain → 401.
    @Test
    void protectedEndpoint_withoutToken_returns401() throws Exception {
        mvc.perform(get("/roles"))
                .andExpect(status().isUnauthorized());
    }

    // A garbage/forged bearer token fails JWT verification → 401.
    @Test
    void protectedEndpoint_withGarbageToken_returns401() throws Exception {
        mvc.perform(get("/roles").header("Authorization", "Bearer not-a-real-jwt"))
                .andExpect(status().isUnauthorized());
    }

    // A valid token from a real login is accepted on a protected endpoint.
    @Test
    void protectedEndpoint_withValidToken_succeeds() throws Exception {
        mvc.perform(get("/roles").header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk());
    }
}
