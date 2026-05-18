package com.oneday.auth.exception;

public class RoleInUseException extends RuntimeException {
    public RoleInUseException(String roleName) {
        super("Role '" + roleName + "' is still assigned to active users and cannot be deactivated");
    }
}
