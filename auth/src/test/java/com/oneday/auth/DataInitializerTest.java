package com.oneday.auth;

import com.oneday.auth.domain.Role;
import com.oneday.auth.domain.RoleAuditLog;
import com.oneday.auth.domain.User;
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
import org.springframework.boot.ApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DataInitializerTest {

    @Mock private UserRepository userRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private RoleAuditLogRepository auditLogRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private ApplicationArguments args;

    @InjectMocks private DataInitializer dataInitializer;

    private Role adminRole;

    @BeforeEach
    void setup() {
        adminRole = new Role();
        ReflectionTestUtils.setField(adminRole, "id", UUID.randomUUID());
        adminRole.setName("ADMIN");
        adminRole.setDisplayName("Admin");
        adminRole.setCityScoped(false);
        adminRole.setBuiltin(true);
        adminRole.setActive(true);
        adminRole.setPermissions(new HashSet<>());
    }

    @Test
    void run_adminAlreadyExists_skipsCreation() throws Exception {
        when(userRepository.existsByEmail("admin@oneday.in")).thenReturn(true);

        dataInitializer.run(args);

        verify(userRepository, never()).save(any());
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void run_adminNotYetCreated_savesUserAndAuditLog() throws Exception {
        when(userRepository.existsByEmail("admin@oneday.in")).thenReturn(false);
        when(roleRepository.findByName("ADMIN")).thenReturn(Optional.of(adminRole));
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$encoded");

        User savedUser = new User();
        UUID adminId = UUID.randomUUID();
        ReflectionTestUtils.setField(savedUser, "id", adminId);
        savedUser.setEmail("admin@oneday.in");
        savedUser.setPasswordHash("$2a$encoded");
        savedUser.setName("Platform Admin");
        savedUser.setRole(adminRole);
        savedUser.setActive(true);
        savedUser.setMustChangePassword(false);
        when(userRepository.save(any(User.class))).thenReturn(savedUser);

        dataInitializer.run(args);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User captured = userCaptor.getValue();
        assertThat(captured.getEmail()).isEqualTo("admin@oneday.in");
        assertThat(captured.getName()).isEqualTo("Platform Admin");
        assertThat(captured.getPasswordHash()).isEqualTo("$2a$encoded");
        assertThat(captured.getRole()).isEqualTo(adminRole);
        assertThat(captured.isActive()).isTrue();
        assertThat(captured.isMustChangePassword()).isFalse();

        ArgumentCaptor<RoleAuditLog> logCaptor = ArgumentCaptor.forClass(RoleAuditLog.class);
        verify(auditLogRepository).save(logCaptor.capture());
        RoleAuditLog log = logCaptor.getValue();
        assertThat(log.getActorId()).isEqualTo(adminId);
        assertThat(log.getTargetUserId()).isEqualTo(adminId);
        assertThat(log.getAction()).isEqualTo("CREATE");
        assertThat(log.getNewRole()).isEqualTo("ADMIN");
    }

    @Test
    void run_adminRoleMissing_throwsIllegalState() {
        when(userRepository.existsByEmail("admin@oneday.in")).thenReturn(false);
        when(roleRepository.findByName("ADMIN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> dataInitializer.run(args))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN role not found");

        verify(userRepository, never()).save(any());
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void run_passwordIsEncoded() throws Exception {
        when(userRepository.existsByEmail("admin@oneday.in")).thenReturn(false);
        when(roleRepository.findByName("ADMIN")).thenReturn(Optional.of(adminRole));
        when(passwordEncoder.encode("Admin1234!")).thenReturn("$2a$hashed");

        User savedUser = new User();
        ReflectionTestUtils.setField(savedUser, "id", UUID.randomUUID());
        savedUser.setEmail("admin@oneday.in");
        savedUser.setPasswordHash("$2a$hashed");
        savedUser.setName("Platform Admin");
        savedUser.setRole(adminRole);
        when(userRepository.save(any(User.class))).thenReturn(savedUser);

        dataInitializer.run(args);

        verify(passwordEncoder).encode("Admin1234!");
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getPasswordHash()).isEqualTo("$2a$hashed");
    }
}
