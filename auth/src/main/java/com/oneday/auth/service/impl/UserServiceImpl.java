package com.oneday.auth.service.impl;

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
import com.oneday.auth.service.UserService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
class UserServiceImpl implements UserService {

    private static final List<String> CITY_SCOPED_ROLES = List.of(
            "STATION_MANAGER", "SUPERVISOR", "HUB_OPERATOR",
            "DELIVERY_ASSOCIATE", "VAN_DRIVER", "CRON_DRIVER", "CALL_CENTER_AGENT");

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RoleAuditLogRepository auditLogRepository;
    private final PasswordEncoder passwordEncoder;

    UserServiceImpl(UserRepository userRepository,
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
    public UserResponse register(RegisterUserRequest request, UUID actorId) {
        if (userRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyExistsException(request.email());
        }

        var role = roleRepository.findByName(request.role())
                .filter(r -> r.isActive())
                .orElseThrow(() -> new RoleNotFoundException("Role not found: " + request.role()));

        boolean needsCity = CITY_SCOPED_ROLES.contains(role.getName());
        if (needsCity && (request.cityId() == null || request.cityId().isBlank())) {
            throw new ForbiddenException("cityId is required for role " + role.getName());
        }

        var actor = userRepository.findById(actorId)
                .orElseThrow(() -> new UserNotFoundException("Actor not found"));

        // Station Manager city-scope enforcement
        if ("STATION_MANAGER".equals(actor.getRole().getName())) {
            if (needsCity && !actor.getCityId().equals(request.cityId())) {
                throw new ForbiddenException("Station Manager can only create users in their own city");
            }
        }

        var user = new User();
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setName(request.name());
        user.setRole(role);
        user.setCityId(needsCity ? request.cityId() : null);
        user.setActive(true);
        user.setMustChangePassword(true);
        user = userRepository.save(user);

        writeAuditLog(actorId, user.getId(), "CREATE", null, role.getName(),
                user.getCityId(), null);

        return toResponse(user);
    }

    @Override
    @Transactional
    public void changeRole(UUID targetUserId, RoleChangeRequest request, UUID actorId) {
        var target = userRepository.findById(targetUserId)
                .orElseThrow(() -> new UserNotFoundException("User not found"));
        var actor = userRepository.findById(actorId)
                .orElseThrow(() -> new UserNotFoundException("Actor not found"));
        var newRole = roleRepository.findById(request.newRoleId())
                .orElseThrow(() -> new RoleNotFoundException("Role not found"));

        if ("STATION_MANAGER".equals(actor.getRole().getName())) {
            enforceStationManagerScope(actor, target, newRole.getName());
        }

        String previousRole = target.getRole().getName();
        target.setRole(newRole);
        if (CITY_SCOPED_ROLES.contains(newRole.getName()) && target.getCityId() == null) {
            throw new ForbiddenException(
                    "Target user has no cityId but new role " + newRole.getName() + " is city-scoped");
        }
        userRepository.save(target);

        writeAuditLog(actorId, targetUserId, "GRANT", previousRole, newRole.getName(),
                target.getCityId(), request.reason());
    }

    @Override
    @Transactional
    public void deactivate(UUID userId, UUID actorId) {
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found"));
        user.setActive(false);
        userRepository.save(user);
        writeAuditLog(actorId, userId, "DEACTIVATE", user.getRole().getName(), null,
                user.getCityId(), null);
    }

    @Override
    @Transactional
    public void reactivate(UUID userId, UUID actorId) {
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found"));
        user.setActive(true);
        userRepository.save(user);
        writeAuditLog(actorId, userId, "REACTIVATE", null, user.getRole().getName(),
                user.getCityId(), null);
    }

    @Override
    @Transactional
    public void updateProfile(UUID userId, UpdateProfileRequest request) {
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found"));
        user.setName(request.name());
        userRepository.save(user);
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse getUser(UUID userId) {
        return userRepository.findById(userId)
                .map(this::toResponse)
                .orElseThrow(() -> new UserNotFoundException("User not found"));
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse getUserByEmail(String email) {
        return userRepository.findByEmail(email)
                .map(this::toResponse)
                .orElseThrow(() -> new UserNotFoundException("User not found: " + email));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuditLogResponse> getAuditLog(UUID userId) {
        return auditLogRepository.findByTargetUserIdOrderByCreatedAtDesc(userId).stream()
                .map(l -> new AuditLogResponse(l.getId(), l.getActorId(), l.getTargetUserId(),
                        l.getAction(), l.getPreviousRole(), l.getNewRole(),
                        l.getCityId(), l.getReason(), l.getCreatedAt()))
                .toList();
    }

    private void enforceStationManagerScope(User actor, User target, String newRoleName) {
        if ("ADMIN".equals(target.getRole().getName()) || "STATION_MANAGER".equals(target.getRole().getName())) {
            throw new ForbiddenException("Station Manager cannot modify Admin or peer Station Manager");
        }
        if ("ADMIN".equals(newRoleName)) {
            throw new ForbiddenException("Station Manager cannot grant ADMIN role");
        }
        if (!actor.getCityId().equals(target.getCityId())) {
            throw new ForbiddenException("Station Manager can only manage users in their own city");
        }
    }

    private void writeAuditLog(UUID actorId, UUID targetUserId, String action,
            String previousRole, String newRole, String cityId, String reason) {
        var log = new RoleAuditLog();
        log.setActorId(actorId);
        log.setTargetUserId(targetUserId);
        log.setAction(action);
        log.setPreviousRole(previousRole);
        log.setNewRole(newRole);
        log.setCityId(cityId);
        log.setReason(reason);
        auditLogRepository.save(log);
    }

    private UserResponse toResponse(User u) {
        return new UserResponse(u.getId(), u.getEmail(), u.getName(),
                u.getRole().getName(), u.getCityId(), u.isActive());
    }
}
