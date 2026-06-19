package com.oneday.dispatch.repository;

import com.oneday.dispatch.domain.DispatchQueue;
import com.oneday.dispatch.domain.TaskStatus;
import com.oneday.dispatch.domain.TaskType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real-Postgres @DataJpaTest (local DB; Flyway builds the V5_* schema). The headline check is the
 * PARTIAL unique index — the one thing H2 could not faithfully reproduce.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DispatchQueueRepositoryTest {

    @Autowired
    DispatchQueueRepository repo;

    @Test
    void failedRowDoesNotBlockReassignment() {
        UUID da = UUID.randomUUID();
        UUID shipment = UUID.randomUUID();
        LocalDate today = LocalDate.now();

        // A prior FAILED attempt exists...
        repo.saveAndFlush(task(da, shipment, today, TaskStatus.FAILED, 0));

        // ...a fresh active row for the SAME (da, shipment, type, date) must be allowed, because the
        // partial unique index excludes FAILED/CANCELLED.
        assertThatCode(() -> repo.saveAndFlush(task(da, shipment, today, TaskStatus.QUEUED, 0)))
                .doesNotThrowAnyException();

        Optional<DispatchQueue> active = repo.findActiveByShipmentIdAndTaskType(shipment, TaskType.PICKUP);
        assertThat(active).isPresent();
        assertThat(active.get().getStatus()).isEqualTo(TaskStatus.QUEUED);
    }

    @Test
    void twoActiveRowsForSameKeyAreRejected() {
        UUID da = UUID.randomUUID();
        UUID shipment = UUID.randomUUID();
        LocalDate today = LocalDate.now();

        repo.saveAndFlush(task(da, shipment, today, TaskStatus.QUEUED, 0));

        // Both rows are active → both fall under the partial unique index → violation.
        assertThatThrownBy(() -> repo.saveAndFlush(task(da, shipment, today, TaskStatus.IN_PROGRESS, 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void returnsQueueInPositionOrder() {
        UUID da = UUID.randomUUID();
        LocalDate today = LocalDate.now();

        repo.saveAndFlush(task(da, UUID.randomUUID(), today, TaskStatus.QUEUED, 2));
        repo.saveAndFlush(task(da, UUID.randomUUID(), today, TaskStatus.QUEUED, 1));

        var queue = repo.findByDaIdAndOperatingDateOrderByQueuePosition(da, today);
        assertThat(queue).extracting(DispatchQueue::getQueuePosition).containsExactly(1, 2);
    }

    private DispatchQueue task(UUID daId, UUID shipmentId, LocalDate date, TaskStatus status, int pos) {
        DispatchQueue d = new DispatchQueue();
        d.setDaId(daId);
        d.setCityId(UUID.randomUUID());
        d.setShipmentId(shipmentId);
        d.setTaskType(TaskType.PICKUP);
        d.setTaskLat(12.97);
        d.setTaskLon(77.61);
        d.setTileId(UUID.randomUUID());
        d.setQueuePosition(pos);
        d.setStatus(status);
        d.setCronSafe(true);
        d.setOperatingDate(date);
        return d;
    }
}
