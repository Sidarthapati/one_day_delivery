package com.oneday.auth.service;

import com.oneday.auth.dto.request.CreateRoleRequest;
import com.oneday.auth.dto.response.RoleResponse;

import java.util.List;
import java.util.UUID;

public interface RoleService {
    RoleResponse createRole(CreateRoleRequest request);
    List<RoleResponse> listAllRoles();
    void deactivateRole(UUID roleId);
}
