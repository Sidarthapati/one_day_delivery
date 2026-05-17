package com.oneday.auth.service.impl;

import com.oneday.auth.domain.Permission;
import com.oneday.auth.domain.Role;
import com.oneday.auth.domain.User;
import com.oneday.auth.dto.response.PermissionCheckResponse;
import com.oneday.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PermissionServiceImplTest {

    @Mock private UserRepository userRepository;

    @InjectMocks private PermissionServiceImpl service;

    private UUID userId;

    @BeforeEach
    void setup() {
        userId = UUID.randomUUID();
    }

    // ── USER NOT FOUND / INACTIVE ─────────────────────────────────────────────

    @Test
    void canDo_userNotFound_returnsFalse() {
        when(userRepository.findByIdWithPermissions(userId)).thenReturn(Optional.empty());

        PermissionCheckResponse resp = service.canDo(userId, "shipment:create", null);

        assertThat(resp.allowed()).isFalse();
        assertThat(resp.reason()).contains("not found");
    }

    @Test
    void canDo_inactiveUser_returnsFalse() {
        User user = realUser(realRole("ADMIN", false, Set.of(perm("shipment:view"))), null);
        user.setActive(false);
        when(userRepository.findByIdWithPermissions(userId)).thenReturn(Optional.of(user));

        PermissionCheckResponse resp = service.canDo(userId, "shipment:view", null);

        assertThat(resp.allowed()).isFalse();
        assertThat(resp.reason()).contains("inactive");
    }

    // ── PERMISSION CHECK (no city) ────────────────────────────────────────────

    @Test
    void canDo_adminHasPermission_noCityParam_returnsTrue() {
        User user = realUser(realRole("ADMIN", false, Set.of(perm("shipment:view"), perm("user:create"))), null);
        when(userRepository.findByIdWithPermissions(userId)).thenReturn(Optional.of(user));

        PermissionCheckResponse resp = service.canDo(userId, "shipment:view", null);

        assertThat(resp.allowed()).isTrue();
        assertThat(resp.reason()).isEqualTo("allowed");
    }

    @Test
    void canDo_roleDoesNotHavePermission_returnsFalse() {
        User user = realUser(realRole("HUB_OPERATOR", true, Set.of(perm("hub:scan"), perm("shipment:view"))), "MUM");
        when(userRepository.findByIdWithPermissions(userId)).thenReturn(Optional.of(user));

        PermissionCheckResponse resp = service.canDo(userId, "shipment:create", "MUM");

        assertThat(resp.allowed()).isFalse();
        assertThat(resp.reason()).contains("does not have permission");
        assertThat(resp.reason()).contains("shipment:create");
    }

    @Test
    void canDo_emptyPermissionSet_returnsFalse() {
        User user = realUser(realRole("CUSTOM", false, Set.of()), null);
        when(userRepository.findByIdWithPermissions(userId)).thenReturn(Optional.of(user));

        PermissionCheckResponse resp = service.canDo(userId, "any:action", null);

        assertThat(resp.allowed()).isFalse();
    }

    // ── CITY-SCOPED CHECKS ────────────────────────────────────────────────────

    @Test
    void canDo_deliveryAssociateMumbai_viewQueueMumbai_returnsTrue() {
        // Scenario from SCENARIOS.md: Priya (DA, MUM) can da:queue:view in MUM
        User user = realUser(realRole("DELIVERY_ASSOCIATE", true, Set.of(
                perm("da:queue:view"), perm("barcode:attach"), perm("scan:event:create"), perm("shipment:view:assigned")
        )), "MUM");
        when(userRepository.findByIdWithPermissions(userId)).thenReturn(Optional.of(user));

        PermissionCheckResponse resp = service.canDo(userId, "da:queue:view", "MUM");

        assertThat(resp.allowed()).isTrue();
    }

    @Test
    void canDo_deliveryAssociateMumbai_viewQueueDelhi_returnsFalse() {
        // Scenario from SCENARIOS.md: Priya (DA, MUM) cannot touch DEL data
        User user = realUser(realRole("DELIVERY_ASSOCIATE", true, Set.of(perm("da:queue:view"))), "MUM");
        when(userRepository.findByIdWithPermissions(userId)).thenReturn(Optional.of(user));

        PermissionCheckResponse resp = service.canDo(userId, "da:queue:view", "DEL");

        assertThat(resp.allowed()).isFalse();
        assertThat(resp.reason()).contains("MUM");
        assertThat(resp.reason()).contains("DEL");
    }

    @Test
    void canDo_adminShipmentView_anyCity_returnsTrue() {
        // ADMIN is not city-scoped → can touch any city
        User user = realUser(realRole("ADMIN", false, Set.of(perm("shipment:view"))), null);
        when(userRepository.findByIdWithPermissions(userId)).thenReturn(Optional.of(user));

        PermissionCheckResponse resp = service.canDo(userId, "shipment:view", "DEL");

        assertThat(resp.allowed()).isTrue();
    }

    @Test
    void canDo_cityScopedRole_noCityParam_skipsCheck_returnsTrue() {
        // City param is null → city check is skipped per design
        User user = realUser(realRole("DELIVERY_ASSOCIATE", true, Set.of(perm("da:queue:view"))), "MUM");
        when(userRepository.findByIdWithPermissions(userId)).thenReturn(Optional.of(user));

        PermissionCheckResponse resp = service.canDo(userId, "da:queue:view", null);

        assertThat(resp.allowed()).isTrue();
    }

    @Test
    void canDo_cityScopedRole_blankCityParam_skipsCheck_returnsTrue() {
        User user = realUser(realRole("DELIVERY_ASSOCIATE", true, Set.of(perm("da:queue:view"))), "MUM");
        when(userRepository.findByIdWithPermissions(userId)).thenReturn(Optional.of(user));

        PermissionCheckResponse resp = service.canDo(userId, "da:queue:view", "  ");

        assertThat(resp.allowed()).isTrue();
    }

    @Test
    void canDo_hubOperator_shipmentCreate_returnsFalse() {
        // Scenario: HUB_OPERATOR cannot book shipments (booking is B2B_USER only)
        User user = realUser(realRole("HUB_OPERATOR", true, Set.of(
                perm("hub:scan"), perm("hub:stand:assign"), perm("hub:bag:manage"), perm("shipment:view")
        )), "DEL");
        when(userRepository.findByIdWithPermissions(userId)).thenReturn(Optional.of(user));

        PermissionCheckResponse resp = service.canDo(userId, "shipment:create", "DEL");

        assertThat(resp.allowed()).isFalse();
        assertThat(resp.reason()).contains("HUB_OPERATOR");
        assertThat(resp.reason()).contains("shipment:create");
    }

    @Test
    void canDo_b2bUser_shipmentCreate_noCity_returnsTrue() {
        // B2B_USER has shipment:create, is not city-scoped
        User user = realUser(realRole("B2B_USER", false, Set.of(perm("shipment:create"), perm("api-key:create:own"))), null);
        when(userRepository.findByIdWithPermissions(userId)).thenReturn(Optional.of(user));

        PermissionCheckResponse resp = service.canDo(userId, "shipment:create", null);

        assertThat(resp.allowed()).isTrue();
    }

    @Test
    void canDo_airlineGha_manifestView_returnsTrue() {
        // Scenario Ch8: GHA can view manifests
        User user = realUser(realRole("AIRLINE_GHA", false, Set.of(perm("manifest:view"), perm("handover:acknowledge"))), "MUM");
        when(userRepository.findByIdWithPermissions(userId)).thenReturn(Optional.of(user));

        PermissionCheckResponse resp = service.canDo(userId, "manifest:view", "MUM");

        assertThat(resp.allowed()).isTrue();
    }

    @Test
    void canDo_airlineGha_shipmentCreate_returnsFalse() {
        // GHA cannot book shipments
        User user = realUser(realRole("AIRLINE_GHA", false, Set.of(perm("manifest:view"), perm("handover:acknowledge"))), "MUM");
        when(userRepository.findByIdWithPermissions(userId)).thenReturn(Optional.of(user));

        PermissionCheckResponse resp = service.canDo(userId, "shipment:create", null);

        assertThat(resp.allowed()).isFalse();
    }

    @Test
    void canDo_stationManager_auditViewCity_matchingCity_returnsTrue() {
        User user = realUser(realRole("STATION_MANAGER", true, Set.of(perm("audit:view:city"))), "MUM");
        when(userRepository.findByIdWithPermissions(userId)).thenReturn(Optional.of(user));

        PermissionCheckResponse resp = service.canDo(userId, "audit:view:city", "MUM");

        assertThat(resp.allowed()).isTrue();
    }

    @Test
    void canDo_stationManager_auditViewCity_differentCity_returnsFalse() {
        User user = realUser(realRole("STATION_MANAGER", true, Set.of(perm("audit:view:city"))), "MUM");
        when(userRepository.findByIdWithPermissions(userId)).thenReturn(Optional.of(user));

        PermissionCheckResponse resp = service.canDo(userId, "audit:view:city", "BOM");

        assertThat(resp.allowed()).isFalse();
    }

    @Test
    void canDo_b2cCustomer_shipmentTrackOwn_returnsTrue() {
        User user = realUser(realRole("B2C_CUSTOMER", false, Set.of(
                perm("shipment:create"), perm("shipment:track:own"), perm("pricing:quote")
        )), null);
        when(userRepository.findByIdWithPermissions(userId)).thenReturn(Optional.of(user));

        PermissionCheckResponse resp = service.canDo(userId, "shipment:track:own", null);

        assertThat(resp.allowed()).isTrue();
    }

    @Test
    void canDo_cronDriver_cronRunConfirm_ownCity_returnsTrue() {
        User user = realUser(realRole("CRON_DRIVER", true, Set.of(
                perm("cron:run:confirm"), perm("scan:event:create"), perm("shipment:view:assigned")
        )), "MUM");
        when(userRepository.findByIdWithPermissions(userId)).thenReturn(Optional.of(user));

        PermissionCheckResponse resp = service.canDo(userId, "cron:run:confirm", "MUM");

        assertThat(resp.allowed()).isTrue();
    }

    // ── HELPERS ───────────────────────────────────────────────────────────────

    private static Permission perm(String action) {
        Permission p = new Permission();
        ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
        p.setAction(action);
        return p;
    }

    private static Role realRole(String name, boolean cityScoped, Set<Permission> permissions) {
        Role role = new Role();
        ReflectionTestUtils.setField(role, "id", UUID.randomUUID());
        role.setName(name);
        role.setDisplayName(name);
        role.setCityScoped(cityScoped);
        role.setBuiltin(true);
        role.setActive(true);
        role.setPermissions(new HashSet<>(permissions));
        return role;
    }

    private User realUser(Role role, String cityId) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", userId);
        user.setEmail("test@oneday.in");
        user.setPasswordHash("$2a$hash");
        user.setName("Test User");
        user.setRole(role);
        user.setCityId(cityId);
        user.setActive(true);
        user.setMustChangePassword(false);
        return user;
    }
}
