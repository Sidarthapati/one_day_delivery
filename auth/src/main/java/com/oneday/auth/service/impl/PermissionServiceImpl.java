package com.oneday.auth.service.impl;

import com.oneday.auth.dto.response.PermissionCheckResponse;
import com.oneday.auth.repository.UserRepository;
import com.oneday.auth.service.PermissionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
class PermissionServiceImpl implements PermissionService {

    private final UserRepository userRepository;

    PermissionServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public PermissionCheckResponse canDo(UUID userId, String action, String cityId) {
        var user = userRepository.findByIdWithPermissions(userId).orElse(null);
        if (user == null || !user.isActive()) {
            return new PermissionCheckResponse(false, "user not found or inactive");
        }

        var role = user.getRole();
        boolean hasPermission = role.getPermissions().stream()
                .anyMatch(p -> p.getAction().equals(action));

        if (!hasPermission) {
            return new PermissionCheckResponse(false,
                    "role " + role.getName() + " does not have permission " + action);
        }

        if (role.isCityScoped() && cityId != null && !cityId.isBlank()) {
            if (!cityId.equals(user.getCityId())) {
                return new PermissionCheckResponse(false,
                        "user city " + user.getCityId() + " does not match requested city " + cityId);
            }
        }

        return new PermissionCheckResponse(true, "allowed");
    }
}
