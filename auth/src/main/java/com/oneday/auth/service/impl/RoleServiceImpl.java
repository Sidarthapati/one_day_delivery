package com.oneday.auth.service.impl;

import com.oneday.auth.domain.Permission;
import com.oneday.auth.domain.Role;
import com.oneday.auth.dto.request.CreateRoleRequest;
import com.oneday.auth.dto.response.RoleResponse;
import com.oneday.auth.exception.ForbiddenException;
import com.oneday.auth.exception.RoleNotFoundException;
import com.oneday.auth.repository.PermissionRepository;
import com.oneday.auth.repository.RoleRepository;
import com.oneday.auth.repository.UserRepository;
import com.oneday.auth.service.RoleService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
class RoleServiceImpl implements RoleService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final UserRepository userRepository;

    RoleServiceImpl(RoleRepository roleRepository,
                    PermissionRepository permissionRepository,
                    UserRepository userRepository) {
        this.roleRepository = roleRepository;
        this.permissionRepository = permissionRepository;
        this.userRepository = userRepository;
    }

    @Override
    @Transactional
    public RoleResponse createRole(CreateRoleRequest request) {
        var permissions = permissionRepository.findAllByActionIn(request.permissions());
        if (permissions.size() != request.permissions().size()) {
            throw new ForbiddenException("One or more permission strings are not valid");
        }

        var role = new Role();
        role.setName(request.name().toUpperCase());
        role.setDisplayName(request.displayName());
        role.setCityScoped(request.cityScoped());
        role.setBuiltin(false);
        role.setActive(true);
        role.setPermissions(new HashSet<>(permissions));
        role = roleRepository.save(role);

        return toResponse(role);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoleResponse> listAllRoles() {
        return roleRepository.findAllByActiveTrueWithPermissions().stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public void deactivateRole(UUID roleId) {
        var role = roleRepository.findById(roleId)
                .orElseThrow(() -> new RoleNotFoundException("Role not found"));
        if (role.isBuiltin()) {
            throw new ForbiddenException("Built-in roles cannot be deactivated");
        }
        if (userRepository.existsByRoleId(roleId)) {
            throw new com.oneday.auth.exception.RoleInUseException(role.getName());
        }
        role.setActive(false);
        roleRepository.save(role);
    }

    private RoleResponse toResponse(Role r) {
        Set<String> perms = r.getPermissions().stream()
                .map(Permission::getAction)
                .collect(Collectors.toSet());
        return new RoleResponse(r.getId(), r.getName(), r.getDisplayName(),
                r.isCityScoped(), r.isBuiltin(), r.isActive(), perms);
    }
}
