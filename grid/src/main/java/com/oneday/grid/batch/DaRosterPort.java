package com.oneday.grid.batch;

import com.oneday.common.domain.Shift;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// The day's DA roster for a city + date + shift. The real impl (DirectoryDaRosterPort) reads M1
// (auth) via the common DaDirectoryPort. NightlyReplanJob uses it to plan a territory per shift.
public interface DaRosterPort {
    List<UUID> getAvailableDaIds(UUID cityId, LocalDate date, Shift shift);
}
