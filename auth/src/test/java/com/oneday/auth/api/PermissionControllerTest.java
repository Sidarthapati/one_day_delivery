package com.oneday.auth.api;

import com.oneday.auth.domain.Role;
import com.oneday.auth.domain.User;
import com.oneday.auth.dto.response.PermissionCheckResponse;
import com.oneday.auth.dto.response.UserResponse;
import com.oneday.auth.repository.ApiKeyRepository;
import com.oneday.auth.security.AuthUserDetails;
import com.oneday.auth.security.SecurityConfig;
import com.oneday.auth.service.AuthService;
import com.oneday.auth.service.PermissionService;
import com.oneday.auth.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PermissionController.class)
@Import(SecurityConfig.class)
class PermissionControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private PermissionService permissionService;
    @MockBean private UserService userService;
    @MockBean private AuthService authService;           // SecurityConfig → JwtAuthenticationFilter
    @MockBean private ApiKeyRepository apiKeyRepository; // SecurityConfig → JwtAuthenticationFilter

    private static final UUID USER_ID = UUID.randomUUID();

    // ── PERMISSION CHECK ──────────────────────────────────────────────────────

    @Test
    void check_allowed_returns200WithAllowedTrue() throws Exception {
        when(permissionService.canDo(eq(USER_ID), eq("shipment:view"), eq("MUM")))
                .thenReturn(new PermissionCheckResponse(true, "allowed"));

        mockMvc.perform(get("/permissions/check")
                        .with(user(buildPrincipal("ADMIN", null)))
                        .param("userId", USER_ID.toString())
                        .param("action", "shipment:view")
                        .param("cityId", "MUM"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.reason").value("allowed"));
    }

    @Test
    void check_notAllowed_returns200WithAllowedFalse() throws Exception {
        when(permissionService.canDo(eq(USER_ID), eq("shipment:create"), eq("DEL")))
                .thenReturn(new PermissionCheckResponse(false,
                        "user city MUM does not match requested city DEL"));

        mockMvc.perform(get("/permissions/check")
                        .with(user(buildPrincipal("ADMIN", null)))
                        .param("userId", USER_ID.toString())
                        .param("action", "shipment:create")
                        .param("cityId", "DEL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.reason").value("user city MUM does not match requested city DEL"));
    }

    @Test
    void check_withoutCityId_passes_noCityConstraint() throws Exception {
        UUID ownId = UUID.randomUUID();
        AuthUserDetails principal = buildPrincipalWithId("B2B_USER", null, ownId);
        when(permissionService.canDo(eq(ownId), eq("api-key:create:own"), isNull()))
                .thenReturn(new PermissionCheckResponse(true, "allowed"));

        mockMvc.perform(get("/permissions/check")
                        .with(user(principal))
                        .param("userId", ownId.toString())
                        .param("action", "api-key:create:own"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true));
    }

    @Test
    void check_inactiveUser_returns200WithAllowedFalse() throws Exception {
        when(permissionService.canDo(eq(USER_ID), eq("da:queue:view"), any()))
                .thenReturn(new PermissionCheckResponse(false, "user not found or inactive"));

        mockMvc.perform(get("/permissions/check")
                        .with(user(buildPrincipal("ADMIN", null)))
                        .param("userId", USER_ID.toString())
                        .param("action", "da:queue:view")
                        .param("cityId", "MUM"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.reason").value("user not found or inactive"));
    }

    @Test
    void check_roleLacksPermission_returns200WithAllowedFalse() throws Exception {
        when(permissionService.canDo(eq(USER_ID), eq("shipment:create"), any()))
                .thenReturn(new PermissionCheckResponse(false,
                        "role HUB_OPERATOR does not have permission shipment:create"));

        mockMvc.perform(get("/permissions/check")
                        .with(user(buildPrincipal("ADMIN", null)))
                        .param("userId", USER_ID.toString())
                        .param("action", "shipment:create")
                        .param("cityId", "DEL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.reason").value("role HUB_OPERATOR does not have permission shipment:create"));
    }

    @Test
    void check_noIdentifier_returns400() throws Exception {
        mockMvc.perform(get("/permissions/check")
                        .with(user(buildPrincipal("ADMIN", null)))
                        .param("action", "shipment:view")
                        .param("cityId", "MUM"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void check_bothUserIdAndEmail_returns400() throws Exception {
        mockMvc.perform(get("/permissions/check")
                        .with(user(buildPrincipal("ADMIN", null)))
                        .param("userId", USER_ID.toString())
                        .param("email", "someone@test.com")
                        .param("action", "shipment:view"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void check_emailParam_admin_resolves_and_returns200() throws Exception {
        UUID targetId = UUID.randomUUID();
        when(userService.getUserByEmail("ravi@oneday.in"))
                .thenReturn(new UserResponse(targetId, "ravi@oneday.in", "Ravi", "DELIVERY_ASSOCIATE", "MUM", true));
        when(permissionService.canDo(eq(targetId), eq("shipment:view"), isNull()))
                .thenReturn(new PermissionCheckResponse(true, "allowed"));

        mockMvc.perform(get("/permissions/check")
                        .with(user(buildPrincipal("ADMIN", null)))
                        .param("email", "ravi@oneday.in")
                        .param("action", "shipment:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true));
    }

    @Test
    void check_emailParam_regularUser_ownEmail_returns200() throws Exception {
        UUID ownId = UUID.randomUUID();
        AuthUserDetails principal = buildPrincipalWithId("B2C_CUSTOMER", null, ownId);
        when(userService.getUserByEmail("own@test.com"))
                .thenReturn(new UserResponse(ownId, "own@test.com", "User", "B2C_CUSTOMER", null, true));
        when(permissionService.canDo(eq(ownId), eq("shipment:view:own"), isNull()))
                .thenReturn(new PermissionCheckResponse(true, "allowed"));

        mockMvc.perform(get("/permissions/check")
                        .with(user(principal))
                        .param("email", "own@test.com")
                        .param("action", "shipment:view:own"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true));
    }

    @Test
    void check_emailParam_regularUser_otherEmail_returns403() throws Exception {
        UUID ownId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        AuthUserDetails principal = buildPrincipalWithId("B2B_USER", null, ownId);
        when(userService.getUserByEmail("other@test.com"))
                .thenReturn(new UserResponse(otherId, "other@test.com", "Other", "B2B_USER", null, true));

        mockMvc.perform(get("/permissions/check")
                        .with(user(principal))
                        .param("email", "other@test.com")
                        .param("action", "shipment:create"))
                .andExpect(status().isForbidden());
    }

    @Test
    void check_missingAction_returns400() throws Exception {
        mockMvc.perform(get("/permissions/check")
                        .with(user(buildPrincipal("ADMIN", null)))
                        .param("userId", USER_ID.toString())
                        .param("cityId", "MUM"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void check_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/permissions/check")
                        .param("userId", USER_ID.toString())
                        .param("action", "shipment:view"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void check_regularUser_ownId_returns200() throws Exception {
        UUID ownId = UUID.randomUUID();
        AuthUserDetails principal = buildPrincipalWithId("B2C_CUSTOMER", null, ownId);
        when(permissionService.canDo(eq(ownId), eq("shipment:view"), isNull()))
                .thenReturn(new PermissionCheckResponse(true, "allowed"));

        mockMvc.perform(get("/permissions/check")
                        .with(user(principal))
                        .param("userId", ownId.toString())
                        .param("action", "shipment:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true));
    }

    @Test
    void check_regularUser_otherUserId_returns403() throws Exception {
        UUID otherUserId = UUID.randomUUID();

        mockMvc.perform(get("/permissions/check")
                        .with(user(buildPrincipal("B2B_USER", null)))
                        .param("userId", otherUserId.toString())
                        .param("action", "shipment:create"))
                .andExpect(status().isForbidden());
    }

    @Test
    void check_callCenterAgent_otherUserId_returns200() throws Exception {
        when(permissionService.canDo(eq(USER_ID), eq("da:queue:view"), eq("BLR")))
                .thenReturn(new PermissionCheckResponse(true, "allowed"));

        mockMvc.perform(get("/permissions/check")
                        .with(user(buildPrincipal("CALL_CENTER_AGENT", "BLR")))
                        .param("userId", USER_ID.toString())
                        .param("action", "da:queue:view")
                        .param("cityId", "BLR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true));
    }

    // Scenario: Admin has shipment:view for any city (no city-scope restriction)
    @Test
    void check_adminPermission_anyCity_returnsAllowedTrue() throws Exception {
        when(permissionService.canDo(eq(USER_ID), eq("shipment:view"), eq("DEL")))
                .thenReturn(new PermissionCheckResponse(true, "allowed"));

        mockMvc.perform(get("/permissions/check")
                        .with(user(buildPrincipal("ADMIN", null)))
                        .param("userId", USER_ID.toString())
                        .param("action", "shipment:view")
                        .param("cityId", "DEL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true));
    }

    // Scenario: DA Mumbai cannot access Delhi queue
    @Test
    void check_deliveryAssociateMumbai_delhiQueue_returnsAllowedFalse() throws Exception {
        when(permissionService.canDo(eq(USER_ID), eq("da:queue:view"), eq("DEL")))
                .thenReturn(new PermissionCheckResponse(false,
                        "user city MUM does not match requested city DEL"));

        mockMvc.perform(get("/permissions/check")
                        .with(user(buildPrincipal("ADMIN", null)))
                        .param("userId", USER_ID.toString())
                        .param("action", "da:queue:view")
                        .param("cityId", "DEL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false));
    }

    // ── HELPERS ───────────────────────────────────────────────────────────────

    private static AuthUserDetails buildPrincipal(String roleName, String cityId) {
        return buildPrincipalWithId(roleName, cityId, UUID.randomUUID());
    }

    private static AuthUserDetails buildPrincipalWithId(String roleName, String cityId, UUID userId) {
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
        ReflectionTestUtils.setField(user, "id", userId);

        return new AuthUserDetails(user);
    }
}
