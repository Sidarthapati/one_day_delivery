package com.oneday.auth.repository;

import com.oneday.auth.domain.Permission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PermissionRepository extends JpaRepository<Permission, UUID> {

    Optional<Permission> findByAction(String action);

    List<Permission> findAllByActionIn(Collection<String> actions);
}
