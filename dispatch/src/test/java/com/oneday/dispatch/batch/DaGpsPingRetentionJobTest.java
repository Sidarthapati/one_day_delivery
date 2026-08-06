package com.oneday.dispatch.batch;

import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.domain.DaGpsPing;
import com.oneday.dispatch.repository.DaGpsPingRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Real-Postgres @DataJpaTest (Flyway builds the schema); excluded from CI which has no DB.
@Tag("e2e")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DaGpsPingRetentionJobTest {

    @Autowired
    DaGpsPingRepository repository;

    @Test
    void purgesTrailRowsOlderThanRetentionWindow_keepsRecent() {
        Instant now = Instant.parse("2026-08-01T00:00:00Z");
        UUID da = UUID.randomUUID();
        repository.save(new DaGpsPing(da, 12.9, 77.6, now.minus(Duration.ofDays(40)))); // stale → purge
        DaGpsPing recent =
                repository.save(new DaGpsPing(da, 12.9, 77.6, now.minus(Duration.ofDays(1)))); // keep
        repository.flush();

        DispatchProperties props = new DispatchProperties();
        props.getGps().setTrailRetentionDays(30);
        DaGpsPingRetentionJob job = new DaGpsPingRetentionJob(repository, props);

        int deleted = job.purge(now);

        assertThat(deleted).isEqualTo(1);
        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findAll()).extracting(DaGpsPing::getId).containsExactly(recent.getId());
    }
}
