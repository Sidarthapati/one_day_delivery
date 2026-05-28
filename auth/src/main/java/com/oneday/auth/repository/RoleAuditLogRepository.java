package com.oneday.auth.repository;

import com.oneday.auth.domain.RoleAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RoleAuditLogRepository extends JpaRepository<RoleAuditLog, UUID> {

    List<RoleAuditLog> findByTargetUserIdOrderByCreatedAtDesc(UUID targetUserId);

    List<RoleAuditLog> findByActorIdOrderByCreatedAtDesc(UUID actorId);
}
