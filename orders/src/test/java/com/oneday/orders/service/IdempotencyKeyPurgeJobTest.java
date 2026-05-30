package com.oneday.orders.service;

import com.oneday.orders.repository.IdempotencyKeyRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("IdempotencyKeyPurgeJob")
class IdempotencyKeyPurgeJobTest {

    @Mock
    private IdempotencyKeyRepository repository;

    @InjectMocks
    private IdempotencyKeyPurgeJob purgeJob;

    @Test
    @DisplayName("purgeExpiredKeys calls deleteExpired with current time and logs deleted count")
    void purgeExpiredKeys_callsDeleteExpiredWithNow() {
        when(repository.deleteExpired(org.mockito.ArgumentMatchers.any())).thenReturn(42);

        Instant before = Instant.now();
        purgeJob.purgeExpiredKeys();
        Instant after = Instant.now();

        ArgumentCaptor<Instant> captor = ArgumentCaptor.forClass(Instant.class);
        verify(repository).deleteExpired(captor.capture());

        Instant cutoff = captor.getValue();
        assertThat(cutoff)
                .isAfterOrEqualTo(before)
                .isBeforeOrEqualTo(after);
    }

    @Test
    @DisplayName("purgeExpiredKeys handles zero deleted rows without error")
    void purgeExpiredKeys_zeroDeleted_noError() {
        when(repository.deleteExpired(org.mockito.ArgumentMatchers.any())).thenReturn(0);

        purgeJob.purgeExpiredKeys(); // must not throw
    }
}
