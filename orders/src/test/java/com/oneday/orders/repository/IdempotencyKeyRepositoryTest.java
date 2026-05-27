package com.oneday.orders.repository;

import com.oneday.orders.domain.IdempotencyKey;
import com.oneday.orders.domain.IdempotencyKeyId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("IdempotencyKeyRepository")
class IdempotencyKeyRepositoryTest extends AbstractRepositoryTest {

    @Autowired
    private IdempotencyKeyRepository repo;

    @Test
    void saveAndFindById_compositeKeyRoundTrip() {
        UUID userId = UUID.randomUUID();
        IdempotencyKey saved = repo.save(
                TestFixtures.idempotencyKey("test-key-001", userId, Instant.now().plus(1, ChronoUnit.HOURS)));

        IdempotencyKeyId id = new IdempotencyKeyId("test-key-001", userId);
        assertThat(repo.findById(id)).isPresent();
        assertThat(saved.getResponseStatus()).isEqualTo((short) 200);
    }

    @Test
    void existsById_falseForUnknownKey() {
        IdempotencyKeyId unknown = new IdempotencyKeyId("no-such-key", UUID.randomUUID());
        assertThat(repo.existsById(unknown)).isFalse();
    }

    @Test
    void deleteExpired_removesOnlyExpiredKeys() {
        UUID userId = UUID.randomUUID();
        Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant future = Instant.now().plus(1, ChronoUnit.HOURS);

        repo.save(TestFixtures.idempotencyKey("expired-key", userId, past));
        repo.save(TestFixtures.idempotencyKey("valid-key", userId, future));

        int deleted = repo.deleteExpired(Instant.now());

        assertThat(deleted).isEqualTo(1);
        assertThat(repo.existsById(new IdempotencyKeyId("expired-key", userId))).isFalse();
        assertThat(repo.existsById(new IdempotencyKeyId("valid-key", userId))).isTrue();
    }
}
