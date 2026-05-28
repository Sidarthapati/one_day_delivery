package com.oneday.auth.api;

import com.oneday.auth.domain.Role;
import com.oneday.auth.domain.User;
import com.oneday.auth.dto.response.ApiKeyCreateResponse;
import com.oneday.auth.dto.response.ApiKeyResponse;
import com.oneday.auth.dto.response.LoginResponse;
import com.oneday.auth.exception.ApiKeyCapExceededException;
import com.oneday.auth.exception.BadCredentialsException;
import com.oneday.auth.exception.EmailAlreadyExistsException;
import com.oneday.auth.repository.ApiKeyRepository;
import com.oneday.auth.security.AuthUserDetails;
import com.oneday.auth.security.SecurityConfig;
import com.oneday.auth.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
class AuthControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private AuthService authService;
    @MockBean private ApiKeyRepository apiKeyRepository;  // needed by SecurityConfig → JwtAuthenticationFilter

    // ── HEALTH ────────────────────────────────────────────────────────────────

    @Test
    void health_returnsUp() throws Exception {
        mockMvc.perform(get("/auth/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    // ── LOGIN ─────────────────────────────────────────────────────────────────

    @Test
    void login_validCredentials_returns200WithToken() throws Exception {
        LoginResponse resp = new LoginResponse("jwt-token", Instant.now().plusSeconds(28800), "ADMIN", null, false);
        when(authService.login(any())).thenReturn(resp);

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "admin@oneday.in", "password": "Admin1234!"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("jwt-token"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.mustChangePassword").value(false));
    }

    @Test
    void login_wrongCredentials_returns401() throws Exception {
        when(authService.login(any())).thenThrow(new BadCredentialsException());

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "admin@oneday.in", "password": "wrong"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_missingEmail_returns422() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"password": "Admin1234!"}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void login_missingPassword_returns422() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "admin@oneday.in"}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void login_invalidEmailFormat_returns422() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "not-an-email", "password": "Admin1234!"}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void login_noBody_returns400() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is4xxClientError());
    }

    // ── SELF-REGISTER ─────────────────────────────────────────────────────────

    @Test
    void register_validRequest_returns200WithToken() throws Exception {
        LoginResponse resp = new LoginResponse("jwt-token", Instant.now().plusSeconds(28800), "B2C_CUSTOMER", null, false);
        when(authService.register(any())).thenReturn(resp);

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "riya@example.com", "password": "Secret1234", "name": "Riya"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("B2C_CUSTOMER"));
    }

    @Test
    void register_duplicateEmail_returns409() throws Exception {
        when(authService.register(any())).thenThrow(new EmailAlreadyExistsException("riya@example.com"));

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "riya@example.com", "password": "Secret1234", "name": "Riya"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void register_shortPassword_returns422() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "user@test.com", "password": "short", "name": "User"}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void register_missingName_returns422() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "user@test.com", "password": "Secret1234"}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── API KEY CREATE ────────────────────────────────────────────────────────

    @Test
    void createApiKey_authenticated_returns200WithRawKey() throws Exception {
        AuthUserDetails principal = buildPrincipal("ADMIN", null);
        UUID keyId = UUID.randomUUID();
        ApiKeyCreateResponse resp = new ApiKeyCreateResponse(keyId, "oms-prod", "raw-key-value", Instant.now());
        when(authService.createApiKey(any(UUID.class), any())).thenReturn(resp);

        mockMvc.perform(post("/auth/api-keys")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label": "oms-prod"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("oms-prod"))
                .andExpect(jsonPath("$.rawKey").value("raw-key-value"));
    }

    @Test
    void createApiKey_capExceeded_returns422() throws Exception {
        AuthUserDetails principal = buildPrincipal("B2B_USER", null);
        when(authService.createApiKey(any(UUID.class), any())).thenThrow(new ApiKeyCapExceededException());

        mockMvc.perform(post("/auth/api-keys")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label": "one-too-many"}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createApiKey_missingLabel_returns422() throws Exception {
        AuthUserDetails principal = buildPrincipal("B2B_USER", null);

        mockMvc.perform(post("/auth/api-keys")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label": ""}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createApiKey_b2cCustomer_returns200() throws Exception {
        AuthUserDetails principal = buildPrincipal("B2C_CUSTOMER", null);
        UUID keyId = UUID.randomUUID();
        ApiKeyCreateResponse resp = new ApiKeyCreateResponse(keyId, "my-app", "raw-key-value", Instant.now());
        when(authService.createApiKey(any(UUID.class), any())).thenReturn(resp);

        mockMvc.perform(post("/auth/api-keys")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label": "my-app"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void createApiKey_forbiddenRole_returns403() throws Exception {
        AuthUserDetails principal = buildPrincipal("DELIVERY_AGENT", null);

        mockMvc.perform(post("/auth/api-keys")
                        .with(user(principal))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label": "oms-prod"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void createApiKey_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/auth/api-keys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label": "oms-prod"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    // ── API KEY LIST ──────────────────────────────────────────────────────────

    @Test
    void listApiKeys_authenticated_returnsKeyList() throws Exception {
        AuthUserDetails principal = buildPrincipal("B2B_USER", null);
        List<ApiKeyResponse> keys = List.of(
                new ApiKeyResponse(UUID.randomUUID(), "oms-prod", true, null, Instant.now()),
                new ApiKeyResponse(UUID.randomUUID(), "staging", false, Instant.now(), Instant.now())
        );
        when(authService.listApiKeys(any(UUID.class))).thenReturn(keys);

        mockMvc.perform(get("/auth/api-keys")
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].label").value("oms-prod"))
                .andExpect(jsonPath("$[0].active").value(true))
                .andExpect(jsonPath("$[1].label").value("staging"))
                .andExpect(jsonPath("$[1].active").value(false));
    }

    @Test
    void listApiKeys_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/auth/api-keys"))
                .andExpect(status().isUnauthorized());
    }

    // ── API KEY REVOKE ────────────────────────────────────────────────────────

    @Test
    void revokeApiKey_owner_returns204() throws Exception {
        AuthUserDetails principal = buildPrincipal("B2B_USER", null);
        UUID keyId = UUID.randomUUID();

        mockMvc.perform(delete("/auth/api-keys/{keyId}", keyId)
                        .with(user(principal)))
                .andExpect(status().isNoContent());

        verify(authService).revokeApiKey(eq(keyId), any(UUID.class));
    }

    @Test
    void revokeApiKey_unauthenticated_returns401() throws Exception {
        mockMvc.perform(delete("/auth/api-keys/{keyId}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    // ── HELPERS ───────────────────────────────────────────────────────────────

    private static AuthUserDetails buildPrincipal(String roleName, String cityId) {
        Role role = new Role();
        role.setName(roleName);
        role.setDisplayName(roleName);
        role.setCityScoped(cityId != null);
        role.setBuiltin(true);
        role.setActive(true);
        ReflectionTestUtils.setField(role, "id", UUID.randomUUID());

        User user = new User();
        user.setEmail("test@oneday.in");
        user.setPasswordHash("$2a$hash");
        user.setName("Test User");
        user.setRole(role);
        user.setCityId(cityId);
        user.setActive(true);
        user.setMustChangePassword(false);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());

        return new AuthUserDetails(user);
    }
}
