package com.oneday.auth.service.impl;

import com.oneday.auth.domain.Permission;
import com.oneday.auth.domain.Role;
import com.oneday.auth.dto.request.CreateRoleRequest;
import com.oneday.auth.dto.response.RoleResponse;
import com.oneday.auth.exception.ForbiddenException;
import com.oneday.auth.exception.RoleInUseException;
import com.oneday.auth.exception.RoleNotFoundException;
import com.oneday.auth.repository.PermissionRepository;
import com.oneday.auth.repository.RoleRepository;
import com.oneday.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoleServiceImplTest {

    @Mock private RoleRepository roleRepository;
    @Mock private PermissionRepository permissionRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks private RoleServiceImpl service;

    // ── CREATE ROLE ───────────────────────────────────────────────────────────

    @Test
    void createRole_validPermissions_returnsRoleResponse() {
        Set<String> permActions = Set.of("shipment:view", "hub:scan");
        List<Permission> perms = List.of(perm("shipment:view"), perm("hub:scan"));
        when(permissionRepository.findAllByActionIn(permActions)).thenReturn(perms);

        Role saved = realRole("CUSTOM_ROLE", "Custom Role", false, false, true);
        when(roleRepository.save(any(Role.class))).thenReturn(saved);

        RoleResponse resp = service.createRole(
                new CreateRoleRequest("custom_role", "Custom Role", false, permActions));

        assertThat(resp.name()).isEqualTo("CUSTOM_ROLE");
        assertThat(resp.cityScoped()).isFalse();
        assertThat(resp.builtin()).isFalse();
        assertThat(resp.active()).isTrue();
    }

    @Test
    void createRole_nameSavedAsUpperCase() {
        Set<String> permActions = Set.of("shipment:view");
        when(permissionRepository.findAllByActionIn(permActions)).thenReturn(List.of(perm("shipment:view")));

        ArgumentCaptor<Role> captor = ArgumentCaptor.forClass(Role.class);
        Role saved = realRole("WAREHOUSE_MANAGER", "Warehouse Manager", true, false, true);
        when(roleRepository.save(captor.capture())).thenReturn(saved);

        service.createRole(new CreateRoleRequest("warehouse_manager", "Warehouse Manager", true, permActions));

        assertThat(captor.getValue().getName()).isEqualTo("WAREHOUSE_MANAGER");
    }

    @Test
    void createRole_cityScoped_savedWithCityScopedTrue() {
        Set<String> permActions = Set.of("da:queue:view");
        when(permissionRepository.findAllByActionIn(permActions)).thenReturn(List.of(perm("da:queue:view")));

        ArgumentCaptor<Role> captor = ArgumentCaptor.forClass(Role.class);
        Role saved = realRole("CITY_ROLE", "City Role", true, false, true);
        when(roleRepository.save(captor.capture())).thenReturn(saved);

        service.createRole(new CreateRoleRequest("city_role", "City Role", true, permActions));

        assertThat(captor.getValue().isCityScoped()).isTrue();
        assertThat(captor.getValue().isBuiltin()).isFalse();
    }

    @Test
    void createRole_notBuiltin_savedWithBuiltinFalse() {
        Set<String> permActions = Set.of("shipment:view");
        when(permissionRepository.findAllByActionIn(permActions)).thenReturn(List.of(perm("shipment:view")));

        ArgumentCaptor<Role> captor = ArgumentCaptor.forClass(Role.class);
        Role saved = realRole("NEW_ROLE", "New Role", false, false, true);
        when(roleRepository.save(captor.capture())).thenReturn(saved);

        service.createRole(new CreateRoleRequest("new_role", "New Role", false, permActions));

        assertThat(captor.getValue().isBuiltin()).isFalse();
        assertThat(captor.getValue().isActive()).isTrue();
    }

    @Test
    void createRole_invalidPermission_throwsForbidden() {
        Set<String> permActions = Set.of("shipment:view", "nonexistent:action");
        // Only 1 found, but 2 were requested
        when(permissionRepository.findAllByActionIn(permActions)).thenReturn(List.of(perm("shipment:view")));

        assertThatThrownBy(() -> service.createRole(
                new CreateRoleRequest("role", "Role", false, permActions)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("not valid");
    }

    @Test
    void createRole_noPermissionsFound_throwsForbidden() {
        Set<String> permActions = Set.of("fake:action");
        when(permissionRepository.findAllByActionIn(permActions)).thenReturn(List.of());

        assertThatThrownBy(() -> service.createRole(
                new CreateRoleRequest("role", "Role", false, permActions)))
                .isInstanceOf(ForbiddenException.class);
    }

    // ── LIST ROLES ────────────────────────────────────────────────────────────

    @Test
    void listAllRoles_returnsOnlyActiveRoles() {
        Role r1 = realRole("ADMIN", "Administrator", false, true, true);
        Role r2 = realRole("DELIVERY_ASSOCIATE", "DA", true, true, true);
        when(roleRepository.findAllByActiveTrueWithPermissions()).thenReturn(List.of(r1, r2));

        List<RoleResponse> roles = service.listAllRoles();

        assertThat(roles).hasSize(2);
        assertThat(roles).extracting(RoleResponse::name)
                .containsExactly("ADMIN", "DELIVERY_ASSOCIATE");
    }

    @Test
    void listAllRoles_empty_returnsEmptyList() {
        when(roleRepository.findAllByActiveTrueWithPermissions()).thenReturn(List.of());

        assertThat(service.listAllRoles()).isEmpty();
    }

    @Test
    void listAllRoles_roleProperties_mappedCorrectly() {
        Role r = realRole("ADMIN", "Administrator", false, true, true);
        when(roleRepository.findAllByActiveTrueWithPermissions()).thenReturn(List.of(r));

        List<RoleResponse> roles = service.listAllRoles();

        RoleResponse resp = roles.get(0);
        assertThat(resp.builtin()).isTrue();
        assertThat(resp.cityScoped()).isFalse();
        assertThat(resp.active()).isTrue();
        assertThat(resp.displayName()).isEqualTo("Administrator");
    }

    @Test
    void listAllRoles_permissionsMappedIntoResponse() {
        Role r = realRole("B2B_USER", "B2B User", false, true, true);
        r.setPermissions(Set.of(perm("shipment:create"), perm("pricing:quote")));
        when(roleRepository.findAllByActiveTrueWithPermissions()).thenReturn(List.of(r));

        RoleResponse resp = service.listAllRoles().get(0);

        assertThat(resp.permissions()).containsExactlyInAnyOrder("shipment:create", "pricing:quote");
    }

    @Test
    void listAllRoles_includes12BuiltinRoles() {
        List<Role> builtins = List.of(
                "ADMIN", "STATION_MANAGER", "SUPERVISOR", "HUB_OPERATOR",
                "DELIVERY_ASSOCIATE", "VAN_DRIVER", "CRON_DRIVER", "CALL_CENTER_AGENT",
                "B2B_USER", "B2C_CUSTOMER", "C2C_CUSTOMER", "AIRLINE_GHA"
        ).stream().map(n -> realRole(n, n, false, true, true)).toList();

        when(roleRepository.findAllByActiveTrueWithPermissions()).thenReturn(builtins);

        List<RoleResponse> roles = service.listAllRoles();

        assertThat(roles).hasSize(12);
        assertThat(roles).extracting(RoleResponse::builtin).containsOnly(true);
    }

    // ── DEACTIVATE ROLE ───────────────────────────────────────────────────────

    @Test
    void deactivateRole_customUnusedRole_success() {
        UUID roleId = UUID.randomUUID();
        Role role = realRole("CUSTOM", "Custom", false, false, true);
        ReflectionTestUtils.setField(role, "id", roleId);
        when(roleRepository.findById(roleId)).thenReturn(Optional.of(role));
        when(userRepository.existsByRoleId(roleId)).thenReturn(false);

        service.deactivateRole(roleId);

        assertThat(role.isActive()).isFalse();
        verify(roleRepository).save(role);
    }

    @Test
    void deactivateRole_builtinRole_throwsForbidden() {
        UUID roleId = UUID.randomUUID();
        Role role = realRole("ADMIN", "Administrator", false, true, true);
        ReflectionTestUtils.setField(role, "id", roleId);
        when(roleRepository.findById(roleId)).thenReturn(Optional.of(role));

        assertThatThrownBy(() -> service.deactivateRole(roleId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Built-in");
    }

    @Test
    void deactivateRole_roleInUse_throwsRoleInUse() {
        UUID roleId = UUID.randomUUID();
        Role role = realRole("CUSTOM", "Custom", false, false, true);
        ReflectionTestUtils.setField(role, "id", roleId);
        when(roleRepository.findById(roleId)).thenReturn(Optional.of(role));
        when(userRepository.existsByRoleId(roleId)).thenReturn(true);

        assertThatThrownBy(() -> service.deactivateRole(roleId))
                .isInstanceOf(RoleInUseException.class)
                .hasMessageContaining("CUSTOM");
    }

    @Test
    void deactivateRole_notFound_throwsRoleNotFound() {
        UUID roleId = UUID.randomUUID();
        when(roleRepository.findById(roleId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deactivateRole(roleId))
                .isInstanceOf(RoleNotFoundException.class);
    }

    @Test
    void deactivateRole_allBuiltinRoles_throwForbidden() {
        // All 12 built-in roles cannot be deactivated
        for (String roleName : List.of("ADMIN", "STATION_MANAGER", "SUPERVISOR", "HUB_OPERATOR",
                "DELIVERY_ASSOCIATE", "VAN_DRIVER", "CRON_DRIVER", "CALL_CENTER_AGENT",
                "B2B_USER", "B2C_CUSTOMER", "C2C_CUSTOMER", "AIRLINE_GHA")) {
            UUID roleId = UUID.randomUUID();
            Role role = realRole(roleName, roleName, false, true, true);
            ReflectionTestUtils.setField(role, "id", roleId);
            when(roleRepository.findById(roleId)).thenReturn(Optional.of(role));

            assertThatThrownBy(() -> service.deactivateRole(roleId))
                    .isInstanceOf(ForbiddenException.class);
        }
    }

    // ── HELPERS ───────────────────────────────────────────────────────────────

    private static Permission perm(String action) {
        Permission p = new Permission();
        ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
        p.setAction(action);
        return p;
    }

    private static Role realRole(String name, String displayName,
                                  boolean cityScoped, boolean builtin, boolean active) {
        Role role = new Role();
        ReflectionTestUtils.setField(role, "id", UUID.randomUUID());
        role.setName(name);
        role.setDisplayName(displayName);
        role.setCityScoped(cityScoped);
        role.setBuiltin(builtin);
        role.setActive(active);
        role.setPermissions(new HashSet<>());
        return role;
    }
}
