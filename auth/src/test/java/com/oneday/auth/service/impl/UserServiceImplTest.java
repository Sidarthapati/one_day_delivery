package com.oneday.auth.service.impl;

import com.oneday.auth.domain.Role;
import com.oneday.auth.domain.RoleAuditLog;
import com.oneday.auth.domain.User;
import com.oneday.auth.dto.request.RegisterUserRequest;
import com.oneday.auth.dto.request.RoleChangeRequest;
import com.oneday.auth.dto.request.UpdateProfileRequest;
import com.oneday.auth.dto.response.AuditLogResponse;
import com.oneday.auth.dto.response.UserResponse;
import com.oneday.auth.exception.EmailAlreadyExistsException;
import com.oneday.auth.exception.ForbiddenException;
import com.oneday.auth.exception.RoleNotFoundException;
import com.oneday.auth.exception.UserNotFoundException;
import com.oneday.auth.repository.RoleAuditLogRepository;
import com.oneday.auth.repository.RoleRepository;
import com.oneday.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock private UserRepository userRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private RoleAuditLogRepository auditLogRepository;
    @Mock private PasswordEncoder passwordEncoder;

    @InjectMocks private UserServiceImpl service;

    private UUID adminId;
    private UUID smId;
    private UUID targetId;
    private Role adminRole;
    private Role smRole;
    private Role daRole;
    private Role supervisorRole;
    private Role b2bRole;
    private User adminUser;
    private User smUser;

    @BeforeEach
    void setup() {
        adminId = UUID.randomUUID();
        smId = UUID.randomUUID();
        targetId = UUID.randomUUID();

        adminRole = realRole("ADMIN", false);
        smRole = realRole("STATION_MANAGER", true);
        daRole = realRole("DELIVERY_ASSOCIATE", true);
        supervisorRole = realRole("SUPERVISOR", true);
        b2bRole = realRole("B2B_USER", false);

        adminUser = realUser(adminId, "admin@oneday.in", adminRole, null);
        smUser = realUser(smId, "arjun@oneday.in", smRole, "MUM");
    }

    // ── REGISTER (admin/SM creates user) ──────────────────────────────────────

    @Test
    void register_adminCreatesStationManager_withCity_success() {
        when(userRepository.existsByEmail("arjun@oneday.in")).thenReturn(false);
        when(roleRepository.findByName("STATION_MANAGER")).thenReturn(Optional.of(smRole));
        when(userRepository.findById(adminId)).thenReturn(Optional.of(adminUser));

        User saved = realUser(UUID.randomUUID(), "arjun@oneday.in", smRole, "MUM");
        when(userRepository.save(any(User.class))).thenReturn(saved);

        UserResponse resp = service.register(
                new RegisterUserRequest("Arjun Sharma", "arjun@oneday.in", "Secure#9012", "STATION_MANAGER", "MUM"),
                adminId);

        assertThat(resp.role()).isEqualTo("STATION_MANAGER");
        assertThat(resp.cityId()).isEqualTo("MUM");
        verify(auditLogRepository).save(any(RoleAuditLog.class));
    }

    @Test
    void register_adminCreatesB2bUser_withoutCity_success() {
        when(userRepository.existsByEmail("farhan@test.com")).thenReturn(false);
        when(roleRepository.findByName("B2B_USER")).thenReturn(Optional.of(b2bRole));
        when(userRepository.findById(adminId)).thenReturn(Optional.of(adminUser));

        User saved = realUser(UUID.randomUUID(), "farhan@test.com", b2bRole, null);
        when(userRepository.save(any(User.class))).thenReturn(saved);

        UserResponse resp = service.register(
                new RegisterUserRequest("Farhan Khan", "farhan@test.com", "Secure#9012", "B2B_USER", null),
                adminId);

        assertThat(resp.role()).isEqualTo("B2B_USER");
        assertThat(resp.cityId()).isNull();
    }

    @Test
    void register_cityScopedRoleWithoutCity_throwsForbidden() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(roleRepository.findByName("DELIVERY_ASSOCIATE")).thenReturn(Optional.of(daRole));

        assertThatThrownBy(() -> service.register(
                new RegisterUserRequest("Priya", "priya@test.com", "Secure#9012", "DELIVERY_ASSOCIATE", null),
                adminId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("cityId is required");
    }

    @Test
    void register_cityScopedRoleWithBlankCity_throwsForbidden() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(roleRepository.findByName("DELIVERY_ASSOCIATE")).thenReturn(Optional.of(daRole));

        assertThatThrownBy(() -> service.register(
                new RegisterUserRequest("Priya", "priya@test.com", "Secure#9012", "DELIVERY_ASSOCIATE", "  "),
                adminId))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void register_duplicateEmail_throwsEmailAlreadyExists() {
        when(userRepository.existsByEmail("dup@test.com")).thenReturn(true);

        assertThatThrownBy(() -> service.register(
                new RegisterUserRequest("Dup", "dup@test.com", "Secure#9012", "STATION_MANAGER", "MUM"),
                adminId))
                .isInstanceOf(EmailAlreadyExistsException.class);
    }

    @Test
    void register_inactiveRole_throwsRoleNotFound() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        Role inactiveRole = realRole("OLD_ROLE", false);
        inactiveRole.setActive(false);
        when(roleRepository.findByName("OLD_ROLE")).thenReturn(Optional.of(inactiveRole));

        assertThatThrownBy(() -> service.register(
                new RegisterUserRequest("User", "user@test.com", "Secure#9012", "OLD_ROLE", null),
                adminId))
                .isInstanceOf(RoleNotFoundException.class);
    }

    @Test
    void register_roleNotFound_throwsRoleNotFound() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(roleRepository.findByName("NONEXISTENT")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.register(
                new RegisterUserRequest("User", "user@test.com", "Secure#9012", "NONEXISTENT", null),
                adminId))
                .isInstanceOf(RoleNotFoundException.class);
    }

    @Test
    void register_stationManagerCreatesUserInOwnCity_success() {
        when(userRepository.existsByEmail("priya@test.com")).thenReturn(false);
        when(roleRepository.findByName("DELIVERY_ASSOCIATE")).thenReturn(Optional.of(daRole));
        when(userRepository.findById(smId)).thenReturn(Optional.of(smUser));

        User saved = realUser(UUID.randomUUID(), "priya@test.com", daRole, "MUM");
        when(userRepository.save(any(User.class))).thenReturn(saved);

        UserResponse resp = service.register(
                new RegisterUserRequest("Priya", "priya@test.com", "Secure#9012", "DELIVERY_ASSOCIATE", "MUM"),
                smId);

        assertThat(resp.role()).isEqualTo("DELIVERY_ASSOCIATE");
        assertThat(resp.cityId()).isEqualTo("MUM");
    }

    @Test
    void register_stationManagerCreatesUserInDifferentCity_throwsForbidden() {
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(roleRepository.findByName("DELIVERY_ASSOCIATE")).thenReturn(Optional.of(daRole));
        when(userRepository.findById(smId)).thenReturn(Optional.of(smUser));

        assertThatThrownBy(() -> service.register(
                new RegisterUserRequest("Rahul", "rahul@test.com", "Secure#9012", "DELIVERY_ASSOCIATE", "DEL"),
                smId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("own city");
    }

    @Test
    void register_newUserGetsMustChangePasswordTrue() {
        when(userRepository.existsByEmail("newbie@test.com")).thenReturn(false);
        when(roleRepository.findByName("DELIVERY_ASSOCIATE")).thenReturn(Optional.of(daRole));
        when(userRepository.findById(adminId)).thenReturn(Optional.of(adminUser));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        User saved = realUser(UUID.randomUUID(), "newbie@test.com", daRole, "MUM");
        when(userRepository.save(captor.capture())).thenReturn(saved);

        service.register(
                new RegisterUserRequest("Newbie", "newbie@test.com", "Secure#9012", "DELIVERY_ASSOCIATE", "MUM"),
                adminId);

        assertThat(captor.getValue().isMustChangePassword()).isTrue();
    }

    @Test
    void register_auditLogActionIsCreate() {
        when(userRepository.existsByEmail("new@test.com")).thenReturn(false);
        when(roleRepository.findByName("DELIVERY_ASSOCIATE")).thenReturn(Optional.of(daRole));
        when(userRepository.findById(adminId)).thenReturn(Optional.of(adminUser));

        User saved = realUser(UUID.randomUUID(), "new@test.com", daRole, "MUM");
        when(userRepository.save(any(User.class))).thenReturn(saved);

        ArgumentCaptor<RoleAuditLog> logCaptor = ArgumentCaptor.forClass(RoleAuditLog.class);

        service.register(
                new RegisterUserRequest("New", "new@test.com", "Secure#9012", "DELIVERY_ASSOCIATE", "MUM"),
                adminId);

        verify(auditLogRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getAction()).isEqualTo("CREATE");
        assertThat(logCaptor.getValue().getNewRole()).isEqualTo("DELIVERY_ASSOCIATE");
        assertThat(logCaptor.getValue().getActorId()).isEqualTo(adminId);
    }

    // ── CHANGE ROLE ───────────────────────────────────────────────────────────

    @Test
    void changeRole_adminPromotesDaToSupervisor_success() {
        User target = realUser(targetId, "priya@test.com", daRole, "MUM");
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(userRepository.findById(adminId)).thenReturn(Optional.of(adminUser));

        UUID supId = supervisorRole.getId();
        when(roleRepository.findById(supId)).thenReturn(Optional.of(supervisorRole));

        service.changeRole(targetId, new RoleChangeRequest(supId, "Strong performance"), adminId);

        assertThat(target.getRole()).isEqualTo(supervisorRole);
        verify(userRepository).save(target);

        ArgumentCaptor<RoleAuditLog> logCaptor = ArgumentCaptor.forClass(RoleAuditLog.class);
        verify(auditLogRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getAction()).isEqualTo("GRANT");
        assertThat(logCaptor.getValue().getPreviousRole()).isEqualTo("DELIVERY_ASSOCIATE");
        assertThat(logCaptor.getValue().getNewRole()).isEqualTo("SUPERVISOR");
    }

    @Test
    void changeRole_stationManagerPromotesInOwnCity_success() {
        User target = realUser(targetId, "priya@test.com", daRole, "MUM");
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(userRepository.findById(smId)).thenReturn(Optional.of(smUser));

        UUID supId = supervisorRole.getId();
        when(roleRepository.findById(supId)).thenReturn(Optional.of(supervisorRole));

        service.changeRole(targetId, new RoleChangeRequest(supId, "Promotion"), smId);

        assertThat(target.getRole()).isEqualTo(supervisorRole);
    }

    @Test
    void changeRole_stationManagerGrantsAdmin_throwsForbidden() {
        User target = realUser(targetId, "priya@test.com", daRole, "MUM");
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(userRepository.findById(smId)).thenReturn(Optional.of(smUser));

        UUID aId = adminRole.getId();
        when(roleRepository.findById(aId)).thenReturn(Optional.of(adminRole));

        assertThatThrownBy(() -> service.changeRole(targetId, new RoleChangeRequest(aId, "Promotion"), smId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("ADMIN");
    }

    @Test
    void changeRole_stationManagerModifiesAdminUser_throwsForbidden() {
        User target = realUser(targetId, "other.admin@test.com", adminRole, null);
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(userRepository.findById(smId)).thenReturn(Optional.of(smUser));

        UUID supId = supervisorRole.getId();
        when(roleRepository.findById(supId)).thenReturn(Optional.of(supervisorRole));

        assertThatThrownBy(() -> service.changeRole(targetId, new RoleChangeRequest(supId, "reason"), smId))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void changeRole_stationManagerModifiesPeerSM_throwsForbidden() {
        User target = realUser(targetId, "other.sm@test.com", smRole, "MUM");
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(userRepository.findById(smId)).thenReturn(Optional.of(smUser));

        UUID supId = supervisorRole.getId();
        when(roleRepository.findById(supId)).thenReturn(Optional.of(supervisorRole));

        assertThatThrownBy(() -> service.changeRole(targetId, new RoleChangeRequest(supId, "reason"), smId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Station Manager");
    }

    @Test
    void changeRole_stationManagerModifiesDifferentCity_throwsForbidden() {
        User target = realUser(targetId, "delhi.da@test.com", daRole, "DEL");
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(userRepository.findById(smId)).thenReturn(Optional.of(smUser));

        UUID supId = supervisorRole.getId();
        when(roleRepository.findById(supId)).thenReturn(Optional.of(supervisorRole));

        assertThatThrownBy(() -> service.changeRole(targetId, new RoleChangeRequest(supId, "reason"), smId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("city");
    }

    @Test
    void changeRole_targetNotFound_throwsUserNotFound() {
        when(userRepository.findById(targetId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeRole(targetId, new RoleChangeRequest(UUID.randomUUID(), null), adminId))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void changeRole_roleNotFound_throwsRoleNotFound() {
        User target = realUser(targetId, "user@test.com", daRole, "MUM");
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(userRepository.findById(adminId)).thenReturn(Optional.of(adminUser));
        when(roleRepository.findById(any(UUID.class))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeRole(targetId, new RoleChangeRequest(UUID.randomUUID(), null), adminId))
                .isInstanceOf(RoleNotFoundException.class);
    }

    @Test
    void changeRole_cityScopedNewRoleTargetHasNoCity_throwsForbidden() {
        // B2B user with no cityId being assigned a city-scoped role
        User target = realUser(targetId, "user@test.com", b2bRole, null);
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));
        when(userRepository.findById(adminId)).thenReturn(Optional.of(adminUser));

        UUID daId = daRole.getId();
        when(roleRepository.findById(daId)).thenReturn(Optional.of(daRole));

        assertThatThrownBy(() -> service.changeRole(targetId, new RoleChangeRequest(daId, "reason"), adminId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("city-scoped");
    }

    // ── DEACTIVATE ────────────────────────────────────────────────────────────

    @Test
    void deactivate_setsActiveFalseAndWritesAuditLog() {
        Role hoRole = realRole("HUB_OPERATOR", true);
        User target = realUser(targetId, "rohan@test.com", hoRole, "MUM");
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));

        service.deactivate(targetId, adminId);

        assertThat(target.isActive()).isFalse();
        verify(userRepository).save(target);

        ArgumentCaptor<RoleAuditLog> logCaptor = ArgumentCaptor.forClass(RoleAuditLog.class);
        verify(auditLogRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getAction()).isEqualTo("DEACTIVATE");
        assertThat(logCaptor.getValue().getActorId()).isEqualTo(adminId);
        assertThat(logCaptor.getValue().getTargetUserId()).isEqualTo(targetId);
        assertThat(logCaptor.getValue().getPreviousRole()).isEqualTo("HUB_OPERATOR");
    }

    @Test
    void deactivate_userNotFound_throwsUserNotFound() {
        when(userRepository.findById(targetId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deactivate(targetId, adminId))
                .isInstanceOf(UserNotFoundException.class);
    }

    // ── REACTIVATE ────────────────────────────────────────────────────────────

    @Test
    void reactivate_setsActiveTrueAndWritesAuditLog() {
        Role hoRole = realRole("HUB_OPERATOR", true);
        User target = realUser(targetId, "rohan@test.com", hoRole, "MUM");
        target.setActive(false);
        when(userRepository.findById(targetId)).thenReturn(Optional.of(target));

        service.reactivate(targetId, adminId);

        assertThat(target.isActive()).isTrue();
        verify(userRepository).save(target);

        ArgumentCaptor<RoleAuditLog> logCaptor = ArgumentCaptor.forClass(RoleAuditLog.class);
        verify(auditLogRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getAction()).isEqualTo("REACTIVATE");
    }

    @Test
    void reactivate_userNotFound_throwsUserNotFound() {
        when(userRepository.findById(targetId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reactivate(targetId, adminId))
                .isInstanceOf(UserNotFoundException.class);
    }

    // ── UPDATE PROFILE ────────────────────────────────────────────────────────

    @Test
    void updateProfile_updatesNameAndSaves() {
        User user = realUser(adminId, "admin@oneday.in", adminRole, null);
        when(userRepository.findById(adminId)).thenReturn(Optional.of(user));

        service.updateProfile(adminId, new UpdateProfileRequest("New Name"));

        assertThat(user.getName()).isEqualTo("New Name");
        verify(userRepository).save(user);
    }

    @Test
    void updateProfile_userNotFound_throwsUserNotFound() {
        when(userRepository.findById(adminId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateProfile(adminId, new UpdateProfileRequest("Name")))
                .isInstanceOf(UserNotFoundException.class);
    }

    // ── GET USER ──────────────────────────────────────────────────────────────

    @Test
    void getUser_found_returnsCorrectResponse() {
        User user = realUser(adminId, "admin@oneday.in", adminRole, null);
        when(userRepository.findById(adminId)).thenReturn(Optional.of(user));

        UserResponse resp = service.getUser(adminId);

        assertThat(resp.email()).isEqualTo("admin@oneday.in");
        assertThat(resp.role()).isEqualTo("ADMIN");
        assertThat(resp.active()).isTrue();
        assertThat(resp.cityId()).isNull();
    }

    @Test
    void getUser_cityScoped_includesCityIdInResponse() {
        User user = realUser(smId, "arjun@oneday.in", smRole, "MUM");
        when(userRepository.findById(smId)).thenReturn(Optional.of(user));

        UserResponse resp = service.getUser(smId);

        assertThat(resp.cityId()).isEqualTo("MUM");
        assertThat(resp.role()).isEqualTo("STATION_MANAGER");
    }

    @Test
    void getUser_notFound_throwsUserNotFound() {
        when(userRepository.findById(adminId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getUser(adminId))
                .isInstanceOf(UserNotFoundException.class);
    }

    // ── GET USER BY EMAIL ─────────────────────────────────────────────────────

    @Test
    void getUserByEmail_existingEmail_returnsUser() {
        User user = realUser(adminId, "admin@oneday.in", adminRole, null);
        when(userRepository.findByEmail("admin@oneday.in")).thenReturn(Optional.of(user));

        UserResponse resp = service.getUserByEmail("admin@oneday.in");

        assertThat(resp.email()).isEqualTo("admin@oneday.in");
        assertThat(resp.role()).isEqualTo("ADMIN");
        assertThat(resp.id()).isEqualTo(adminId);
    }

    @Test
    void getUserByEmail_unknownEmail_throwsUserNotFound() {
        when(userRepository.findByEmail("ghost@test.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getUserByEmail("ghost@test.com"))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessageContaining("ghost@test.com");
    }

    // ── AUDIT LOG ─────────────────────────────────────────────────────────────

    @Test
    void getAuditLog_returnsLogsNewestFirst() {
        RoleAuditLog log1 = fakeLog(adminId, targetId, "CREATE", null, "DELIVERY_ASSOCIATE", "MUM", null);
        RoleAuditLog log2 = fakeLog(smId, targetId, "GRANT", "DELIVERY_ASSOCIATE", "SUPERVISOR", "MUM", "Strong perf");

        // Repository returns newest first (mock simulates that ordering)
        when(auditLogRepository.findByTargetUserIdOrderByCreatedAtDesc(targetId))
                .thenReturn(List.of(log2, log1));

        List<AuditLogResponse> logs = service.getAuditLog(targetId);

        assertThat(logs).hasSize(2);
        assertThat(logs.get(0).action()).isEqualTo("GRANT");
        assertThat(logs.get(0).newRole()).isEqualTo("SUPERVISOR");
        assertThat(logs.get(0).previousRole()).isEqualTo("DELIVERY_ASSOCIATE");
        assertThat(logs.get(0).reason()).isEqualTo("Strong perf");
        assertThat(logs.get(1).action()).isEqualTo("CREATE");
    }

    @Test
    void getAuditLog_noLogs_returnsEmpty() {
        when(auditLogRepository.findByTargetUserIdOrderByCreatedAtDesc(targetId))
                .thenReturn(List.of());

        assertThat(service.getAuditLog(targetId)).isEmpty();
    }

    // ── HELPERS ───────────────────────────────────────────────────────────────

    static Role realRole(String name, boolean cityScoped) {
        Role role = new Role();
        ReflectionTestUtils.setField(role, "id", UUID.randomUUID());
        role.setName(name);
        role.setDisplayName(name);
        role.setCityScoped(cityScoped);
        role.setBuiltin(true);
        role.setActive(true);
        role.setPermissions(new HashSet<>());
        return role;
    }

    static User realUser(UUID id, String email, Role role, String cityId) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        user.setEmail(email);
        user.setPasswordHash("$2a$hash");
        user.setName("Test User");
        user.setRole(role);
        user.setCityId(cityId);
        user.setActive(true);
        user.setMustChangePassword(false);
        return user;
    }

    private static RoleAuditLog fakeLog(UUID actorId, UUID targetId, String action,
                                         String prevRole, String newRole, String cityId, String reason) {
        RoleAuditLog log = new RoleAuditLog();
        log.setActorId(actorId);
        log.setTargetUserId(targetId);
        log.setAction(action);
        log.setPreviousRole(prevRole);
        log.setNewRole(newRole);
        log.setCityId(cityId);
        log.setReason(reason);
        return log;
    }
}
