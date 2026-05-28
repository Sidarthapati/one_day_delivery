package com.oneday.grid.batch;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// Sourced from M1 (auth) once that module is implemented.
// The NightlyReplanJob uses this to determine how many DAs are available per city per day.
// When M1 provides a real implementation, annotate it @Primary to override NoOpDaRosterPort.
public interface DaRosterPort {
    List<UUID> getAvailableDaIds(UUID cityId, LocalDate date);
}
