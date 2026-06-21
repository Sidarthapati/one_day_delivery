package com.oneday.dispatch.metrics;

import com.oneday.dispatch.service.DaStatusService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DispatchHealthIndicatorTest {

    private final DaStatusService daStatusService = mock(DaStatusService.class);
    private final DispatchHealthIndicator indicator = new DispatchHealthIndicator(daStatusService);

    @Test
    void reportsShiftLoadedWhenDasPresent() {
        when(daStatusService.loadedDaIds()).thenReturn(Set.of(UUID.randomUUID(), UUID.randomUUID()));
        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("loadedDas", 2).containsEntry("shiftLoaded", true);
    }

    @Test
    void reportsNoShiftWhenEmpty() {
        when(daStatusService.loadedDaIds()).thenReturn(Set.of());
        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("loadedDas", 0).containsEntry("shiftLoaded", false);
    }
}
