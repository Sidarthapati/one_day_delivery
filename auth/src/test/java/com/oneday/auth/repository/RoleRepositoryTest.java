package com.oneday.auth.repository;

import com.oneday.auth.domain.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RoleRepositoryTest {

    @Autowired RoleRepository roleRepository;

    // ── findByName ────────────────────────────────────────────────────────────

    @Test
    void findByName_seededRole_returnsRole() {
        var result = roleRepository.findByName("ADMIN");

        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("ADMIN");
        assertThat(result.get().isBuiltin()).isTrue();
    }

    @Test
    void findByName_unknownName_returnsEmpty() {
        assertThat(roleRepository.findByName("DOES_NOT_EXIST")).isEmpty();
    }

    // ── findAllByActiveTrueWithPermissions ────────────────────────────────────

    @Test
    void findAllByActiveTrueWithPermissions_returnsOnlyActiveRoles() {
        List<Role> roles = roleRepository.findAllByActiveTrueWithPermissions();

        assertThat(roles).isNotEmpty();
        assertThat(roles).allMatch(Role::isActive);
    }

    @Test
    void findAllByActiveTrueWithPermissions_includesAll12BuiltinRoles() {
        List<Role> roles = roleRepository.findAllByActiveTrueWithPermissions();

        assertThat(roles).extracting(Role::getName).contains(
                "ADMIN", "STATION_MANAGER", "SUPERVISOR",
                "HUB_OPERATOR", "DELIVERY_ASSOCIATE", "VAN_DRIVER",
                "CRON_DRIVER", "CALL_CENTER_AGENT", "B2B_USER",
                "B2C_CUSTOMER", "C2C_CUSTOMER", "AIRLINE_GHA"
        );
    }

    @Test
    void findAllByActiveTrueWithPermissions_excludesInactiveRoles() {
        Role inactive = new Role();
        inactive.setName("TEST_INACTIVE_ROLE");
        inactive.setDisplayName("Test Inactive Role");
        inactive.setCityScoped(false);
        inactive.setBuiltin(false);
        inactive.setActive(false);
        roleRepository.saveAndFlush(inactive);

        List<Role> roles = roleRepository.findAllByActiveTrueWithPermissions();

        assertThat(roles).noneMatch(r -> "TEST_INACTIVE_ROLE".equals(r.getName()));
    }

    @Test
    void findAllByActiveTrueWithPermissions_permissionsEagerlyLoaded() {
        Role active = new Role();
        active.setName("TEST_ACTIVE_ROLE");
        active.setDisplayName("Test Active Role");
        active.setCityScoped(false);
        active.setBuiltin(false);
        active.setActive(true);
        roleRepository.saveAndFlush(active);

        List<Role> roles = roleRepository.findAllByActiveTrueWithPermissions();

        assertThat(roles).anyMatch(r -> "TEST_ACTIVE_ROLE".equals(r.getName()));
        // Permissions collection is initialised (not a lazy proxy) — safe to call outside a session
        roles.forEach(r -> assertThat(r.getPermissions()).isNotNull());
    }
}
