package com.oneday.auth;

import com.oneday.auth.domain.RoleAuditLog;
import com.oneday.auth.domain.User;
import com.oneday.auth.repository.RoleAuditLogRepository;
import com.oneday.auth.repository.RoleRepository;
import com.oneday.auth.repository.UserRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class DataInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RoleAuditLogRepository auditLogRepository;
    private final PasswordEncoder passwordEncoder;

    DataInitializer(UserRepository userRepository,
                    RoleRepository roleRepository,
                    RoleAuditLogRepository auditLogRepository,
                    PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.auditLogRepository = auditLogRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.existsByEmail("admin@oneday.in")) return;

        var adminRole = roleRepository.findByName("ADMIN")
                .orElseThrow(() -> new IllegalStateException("ADMIN role not found — run Flyway migrations first"));

        var admin = new User();
        admin.setEmail("admin@oneday.in");
        admin.setPasswordHash(passwordEncoder.encode("Admin1234!"));
        admin.setName("Platform Admin");
        admin.setRole(adminRole);
        admin.setActive(true);
        admin.setMustChangePassword(false);
        admin = userRepository.save(admin);

        var log = new RoleAuditLog();
        log.setActorId(admin.getId());
        log.setTargetUserId(admin.getId());
        log.setAction("CREATE");
        log.setNewRole("ADMIN");
        auditLogRepository.save(log);
    }
}
