package com.oneday.auth.api;

import com.oneday.auth.domain.Role;
import com.oneday.auth.domain.User;
import com.oneday.auth.dto.response.RoleResponse;
import com.oneday.auth.exception.ForbiddenException;
import com.oneday.auth.exception.RoleInUseException;
import com.oneday.auth.exception.RoleNotFoundException;
import com.oneday.auth.repository.ApiKeyRepository;
import com.oneday.auth.security.AuthUserDetails;
import com.oneday.auth.security.SecurityConfig;
import com.oneday.auth.service.AuthService;
import com.oneday.auth.service.RoleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RoleController.class)
@Import(SecurityConfig.class)
class RoleControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private RoleService roleService;
    @MockBean private AuthService authService;           // SecurityConfig → JwtAuthenticationFilter
    @MockBean private ApiKeyRepository apiKeyRepository; // SecurityConfig → JwtAuthenticationFilter

    private static final UUID ROLE_ID = UUID.randomUUID();

    // ── CREATE ROLE ───────────────────────────────────────────────────────────

    @Test
    void createRole_admin_returns200WithRoleDetails() throws Exception {
        RoleResponse resp = new RoleResponse(ROLE_ID, "WAREHOUSE_MANAGER", "Warehouse Manager", true, false, true, Set.of());
        when(roleService.createRole(any())).thenReturn(resp);

        mockMvc.perform(post("/roles")
                        .with(user(buildPrincipal("ADMIN", null)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "warehouse_manager",
                                  "displayName": "Warehouse Manager",
                                  "cityScoped": true,
                                  "permissions": ["hub:scan", "shipment:view"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("WAREHOUSE_MANAGER"))
                .andExpect(jsonPath("$.cityScoped").value(true))
                .andExpect(jsonPath("$.builtin").value(false));
    }

    @Test
    void createRole_invalidPermissions_returns403() throws Exception {
        when(roleService.createRole(any()))
                .thenThrow(new ForbiddenException("One or more permission strings are not valid"));

        mockMvc.perform(post("/roles")
                        .with(user(buildPrincipal("ADMIN", null)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "bad_role",
                                  "displayName": "Bad Role",
                                  "cityScoped": false,
                                  "permissions": ["fake:action"]
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void createRole_missingName_returns422() throws Exception {
        mockMvc.perform(post("/roles")
                        .with(user(buildPrincipal("ADMIN", null)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "displayName": "Bad Role",
                                  "cityScoped": false,
                                  "permissions": ["hub:scan"]
                                }
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createRole_emptyPermissions_returns422() throws Exception {
        mockMvc.perform(post("/roles")
                        .with(user(buildPrincipal("ADMIN", null)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "empty_role",
                                  "displayName": "Empty Role",
                                  "cityScoped": false,
                                  "permissions": []
                                }
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void createRole_nonAdmin_returns403() throws Exception {
        mockMvc.perform(post("/roles")
                        .with(user(buildPrincipal("VAN_DRIVER", "MUM")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "some_role",
                                  "displayName": "Some Role",
                                  "cityScoped": false,
                                  "permissions": ["hub:scan"]
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void createRole_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "some_role",
                                  "displayName": "Some Role",
                                  "cityScoped": false,
                                  "permissions": ["hub:scan"]
                                }
                                """))
                .andExpect(status().isUnauthorized());
    }

    // ── LIST ROLES ────────────────────────────────────────────────────────────

    @Test
    void listRoles_authenticated_returnsAllActiveRoles() throws Exception {
        List<RoleResponse> roles = List.of(
                new RoleResponse(UUID.randomUUID(), "ADMIN", "Administrator", false, true, true, Set.of()),
                new RoleResponse(UUID.randomUUID(), "DELIVERY_ASSOCIATE", "Delivery Associate", true, true, true, Set.of()),
                new RoleResponse(UUID.randomUUID(), "B2B_USER", "B2B User", false, true, true, Set.of())
        );
        when(roleService.listAllRoles()).thenReturn(roles);

        mockMvc.perform(get("/roles")
                        .with(user(buildPrincipal("ADMIN", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].name").value("ADMIN"))
                .andExpect(jsonPath("$[1].name").value("DELIVERY_ASSOCIATE"))
                .andExpect(jsonPath("$[1].cityScoped").value(true))
                .andExpect(jsonPath("$[2].name").value("B2B_USER"));
    }

    @Test
    void listRoles_emptyList_returnsEmptyArray() throws Exception {
        when(roleService.listAllRoles()).thenReturn(List.of());

        mockMvc.perform(get("/roles")
                        .with(user(buildPrincipal("ADMIN", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void listRoles_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/roles"))
                .andExpect(status().isUnauthorized());
    }

    // ── DEACTIVATE ROLE ───────────────────────────────────────────────────────

    @Test
    void deactivateRole_admin_returns204() throws Exception {
        doNothing().when(roleService).deactivateRole(any(UUID.class));

        mockMvc.perform(delete("/roles/{id}", ROLE_ID)
                        .with(user(buildPrincipal("ADMIN", null))))
                .andExpect(status().isNoContent());

        verify(roleService).deactivateRole(ROLE_ID);
    }

    @Test
    void deactivateRole_builtinRole_returns403() throws Exception {
        doThrow(new ForbiddenException("Built-in roles cannot be deactivated"))
                .when(roleService).deactivateRole(any(UUID.class));

        mockMvc.perform(delete("/roles/{id}", ROLE_ID)
                        .with(user(buildPrincipal("ADMIN", null))))
                .andExpect(status().isForbidden());
    }

    @Test
    void deactivateRole_roleInUse_returns422() throws Exception {
        doThrow(new RoleInUseException("CUSTOM_ROLE"))
                .when(roleService).deactivateRole(any(UUID.class));

        mockMvc.perform(delete("/roles/{id}", ROLE_ID)
                        .with(user(buildPrincipal("ADMIN", null))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void deactivateRole_notFound_returns404() throws Exception {
        doThrow(new RoleNotFoundException("Role not found"))
                .when(roleService).deactivateRole(any(UUID.class));

        mockMvc.perform(delete("/roles/{id}", ROLE_ID)
                        .with(user(buildPrincipal("ADMIN", null))))
                .andExpect(status().isNotFound());
    }

    @Test
    void deactivateRole_nonAdmin_returns403() throws Exception {
        mockMvc.perform(delete("/roles/{id}", ROLE_ID)
                        .with(user(buildPrincipal("VAN_DRIVER", "MUM"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void deactivateRole_unauthenticated_returns401() throws Exception {
        mockMvc.perform(delete("/roles/{id}", ROLE_ID))
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
