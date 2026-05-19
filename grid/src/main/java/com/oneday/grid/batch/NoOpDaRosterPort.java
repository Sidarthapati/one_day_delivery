package com.oneday.grid.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// Placeholder until M1 (auth) provides a real DA roster.
// Returns empty list — the nightly job will flag all tiles as understaffed.
// When M1 is ready, provide a real implementation and annotate it @Primary.
@Component
class NoOpDaRosterPort implements DaRosterPort {

    private static final Logger log = LoggerFactory.getLogger(NoOpDaRosterPort.class);

    @Override
    public List<UUID> getAvailableDaIds(UUID cityId, LocalDate date) {
        log.warn("DA_ROSTER_UNAVAILABLE cityId={} date={} — M1 not yet integrated; returning empty DA list", cityId, date);
        return List.of();
    }
}
