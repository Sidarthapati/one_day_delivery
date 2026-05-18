package com.oneday.auth.repository;

import com.oneday.auth.domain.RoleAuditLog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RoleAuditLogRepositoryTest {

    @Autowired RoleAuditLogRepository roleAuditLogRepository;

    private final UUID actor1    = UUID.randomUUID();
    private final UUID actor2    = UUID.randomUUID();
    private final UUID target1   = UUID.randomUUID();
    private final UUID target2   = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // actor1 acted on two different targets
        roleAuditLogRepository.save(buildLog(actor1, target1, "CREATE"));
        roleAuditLogRepository.save(buildLog(actor1, target2, "ROLE_CHANGE"));
        // actor2 acted on target1 only
        roleAuditLogRepository.save(buildLog(actor2, target1, "DEACTIVATE"));
        roleAuditLogRepository.flush();
    }

    private RoleAuditLog buildLog(UUID actorId, UUID targetUserId, String action) {
        RoleAuditLog log = new RoleAuditLog();
        log.setActorId(actorId);
        log.setTargetUserId(targetUserId);
        log.setAction(action);
        return log;
    }

    // ── findByTargetUserIdOrderByCreatedAtDesc ────────────────────────────────

    @Test
    void findByTargetUserIdOrderByCreatedAtDesc_returnsLogsForThatTarget() {
        List<RoleAuditLog> logs = roleAuditLogRepository
                .findByTargetUserIdOrderByCreatedAtDesc(target1);

        assertThat(logs).hasSize(2);
        assertThat(logs).extracting(RoleAuditLog::getTargetUserId)
                .containsOnly(target1);
    }

    @Test
    void findByTargetUserIdOrderByCreatedAtDesc_doesNotReturnOtherTargetsLogs() {
        List<RoleAuditLog> logs = roleAuditLogRepository
                .findByTargetUserIdOrderByCreatedAtDesc(target2);

        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getActorId()).isEqualTo(actor1);
        assertThat(logs.get(0).getAction()).isEqualTo("ROLE_CHANGE");
    }

    @Test
    void findByTargetUserIdOrderByCreatedAtDesc_unknownTarget_returnsEmpty() {
        assertThat(roleAuditLogRepository
                .findByTargetUserIdOrderByCreatedAtDesc(UUID.randomUUID()))
                .isEmpty();
    }

    // ── findByActorIdOrderByCreatedAtDesc ─────────────────────────────────────

    @Test
    void findByActorIdOrderByCreatedAtDesc_returnsLogsForThatActor() {
        List<RoleAuditLog> logs = roleAuditLogRepository
                .findByActorIdOrderByCreatedAtDesc(actor1);

        assertThat(logs).hasSize(2);
        assertThat(logs).extracting(RoleAuditLog::getActorId)
                .containsOnly(actor1);
    }

    @Test
    void findByActorIdOrderByCreatedAtDesc_doesNotReturnOtherActorsLogs() {
        List<RoleAuditLog> logs = roleAuditLogRepository
                .findByActorIdOrderByCreatedAtDesc(actor2);

        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getTargetUserId()).isEqualTo(target1);
        assertThat(logs.get(0).getAction()).isEqualTo("DEACTIVATE");
    }

    @Test
    void findByActorIdOrderByCreatedAtDesc_unknownActor_returnsEmpty() {
        assertThat(roleAuditLogRepository
                .findByActorIdOrderByCreatedAtDesc(UUID.randomUUID()))
                .isEmpty();
    }
}
