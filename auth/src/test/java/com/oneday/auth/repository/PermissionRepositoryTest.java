package com.oneday.auth.repository;

import com.oneday.auth.domain.Permission;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PermissionRepositoryTest {

    @Autowired PermissionRepository permissionRepository;

    // ── findByAction ──────────────────────────────────────────────────────────

    @Test
    void findByAction_seededPermission_returnsPermission() {
        var result = permissionRepository.findByAction("shipment:view");

        assertThat(result).isPresent();
        assertThat(result.get().getAction()).isEqualTo("shipment:view");
    }

    @Test
    void findByAction_unknownAction_returnsEmpty() {
        assertThat(permissionRepository.findByAction("totally:fake:action")).isEmpty();
    }

    // ── findAllByActionIn ─────────────────────────────────────────────────────

    @Test
    void findAllByActionIn_allMatch_returnsAll() {
        List<Permission> results = permissionRepository.findAllByActionIn(
                List.of("shipment:create", "shipment:view"));

        assertThat(results).hasSize(2);
        assertThat(results).extracting(Permission::getAction)
                .containsExactlyInAnyOrder("shipment:create", "shipment:view");
    }

    @Test
    void findAllByActionIn_partialMatch_returnsOnlyExisting() {
        List<Permission> results = permissionRepository.findAllByActionIn(
                List.of("shipment:view", "totally:fake:action"));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getAction()).isEqualTo("shipment:view");
    }

    @Test
    void findAllByActionIn_noMatch_returnsEmpty() {
        List<Permission> results = permissionRepository.findAllByActionIn(
                List.of("fake:one", "fake:two"));

        assertThat(results).isEmpty();
    }

    @Test
    void findAllByActionIn_largerSubset_returnsMatchingPermissions() {
        List<Permission> results = permissionRepository.findAllByActionIn(
                List.of("user:create", "user:deactivate", "user:role:change", "audit:view"));

        assertThat(results).hasSize(4);
        assertThat(results).extracting(Permission::getAction)
                .containsExactlyInAnyOrder("user:create", "user:deactivate", "user:role:change", "audit:view");
    }
}
