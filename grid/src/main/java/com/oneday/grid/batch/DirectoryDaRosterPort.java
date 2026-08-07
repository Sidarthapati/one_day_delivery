package com.oneday.grid.batch;

import com.oneday.common.domain.Shift;
import com.oneday.common.port.DaDirectoryPort;
import com.oneday.grid.service.GridService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The real DA roster: translates the grid city UUID to its city code and delegates to auth's
 * {@link DaDirectoryPort} (registered DELIVERY_ASSOCIATE users on the shift whose contract covers
 * the date). Replaces the former no-op placeholder now that M1 provides the roster.
 */
@Component
class DirectoryDaRosterPort implements DaRosterPort {

    private static final Logger log = LoggerFactory.getLogger(DirectoryDaRosterPort.class);

    private final DaDirectoryPort daDirectoryPort;
    private final GridService gridService;

    DirectoryDaRosterPort(DaDirectoryPort daDirectoryPort, GridService gridService) {
        this.daDirectoryPort = daDirectoryPort;
        this.gridService = gridService;
    }

    @Override
    public List<UUID> getAvailableDaIds(UUID cityId, LocalDate date, Shift shift) {
        String cityCode = gridService.resolveCityCode(cityId);
        if (cityCode == null) {
            log.warn("DA_ROSTER_NO_CITY_CODE cityId={} — cannot resolve to a city code; empty roster", cityId);
            return List.of();
        }
        List<UUID> daIds = daDirectoryPort.availableDaIds(cityCode, date, shift);
        log.info("DA roster cityCode={} date={} shift={} daCount={}", cityCode, date, shift, daIds.size());
        return daIds;
    }
}
