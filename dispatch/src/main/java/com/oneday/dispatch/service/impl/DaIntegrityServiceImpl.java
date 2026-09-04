package com.oneday.dispatch.service.impl;

import com.oneday.common.port.DaDirectoryPort;
import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.domain.DaStatus;
import com.oneday.dispatch.dto.response.DaIntegritySummary;
import com.oneday.dispatch.repository.DaGpsPingRepository;
import com.oneday.dispatch.repository.DaPingIntegrityAggregate;
import com.oneday.dispatch.repository.DaStatusRepository;
import com.oneday.dispatch.service.DaIntegrityService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rolls up the per-ping risk signals into a GREEN/AMBER/RED standing per DA. RED = a mock-provider fix
 * or a high-risk fix; AMBER = some flagged fixes; GREEN = clean. Worst-trust first so ops triage from
 * the top. City is resolved from {@code da_status}; the name from the DA directory.
 */
@Service
class DaIntegrityServiceImpl implements DaIntegrityService {

    private static final int RED_RISK = 60; // matches the mock-flag weight — an unambiguous cheat

    private final DaGpsPingRepository pingRepository;
    private final DaStatusRepository daStatusRepository;
    private final DaDirectoryPort daDirectory;
    private final DispatchProperties props;

    DaIntegrityServiceImpl(DaGpsPingRepository pingRepository, DaStatusRepository daStatusRepository,
                           DaDirectoryPort daDirectory, DispatchProperties props) {
        this.pingRepository = pingRepository;
        this.daStatusRepository = daStatusRepository;
        this.daDirectory = daDirectory;
        this.props = props;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DaIntegritySummary> summariesForDate(LocalDate date, UUID scopeCityId) {
        ZoneId zone = ZoneId.of(props.getAttendance().getZone());
        Instant from = date.atStartOfDay(zone).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(zone).toInstant();

        List<DaPingIntegrityAggregate> rows = pingRepository.aggregateIntegrityByDa(from, to);

        // City per DA (from da_status), applying the caller's city scope.
        Map<UUID, UUID> cityByDa = new java.util.HashMap<>();
        List<UUID> inScope = new java.util.ArrayList<>();
        for (DaPingIntegrityAggregate r : rows) {
            UUID cityId = daStatusRepository.findByDaId(r.getDaId()).map(DaStatus::getCityId).orElse(null);
            if (scopeCityId != null && !scopeCityId.equals(cityId)) {
                continue; // outside this station manager's city
            }
            cityByDa.put(r.getDaId(), cityId);
            inScope.add(r.getDaId());
        }
        Map<UUID, DaDirectoryPort.DaContact> names = daDirectory.contactsFor(inScope);

        return rows.stream()
                .filter(r -> cityByDa.containsKey(r.getDaId()))
                .map(r -> toSummary(r, cityByDa.get(r.getDaId()), names.get(r.getDaId())))
                .sorted(Comparator.comparingInt((DaIntegritySummary s) -> rank(s.trustLevel())).reversed()
                        .thenComparing(Comparator.comparingInt(DaIntegritySummary::maxRiskScore).reversed()))
                .toList();
    }

    private DaIntegritySummary toSummary(DaPingIntegrityAggregate r, UUID cityId,
                                         DaDirectoryPort.DaContact contact) {
        int maxRisk = r.getMaxRisk() != null ? r.getMaxRisk() : 0;
        String level;
        if (r.getMockedCount() > 0 || maxRisk >= RED_RISK) {
            level = "RED";
        } else if (r.getFlagged() > 0) {
            level = "AMBER";
        } else {
            level = "GREEN";
        }
        return new DaIntegritySummary(r.getDaId(), contact != null ? contact.name() : null, cityId,
                r.getTotal(), r.getFlagged(), maxRisk, r.getMockedCount(), r.getVelocityCount(),
                r.getSkewCount(), level);
    }

    private int rank(String level) {
        return switch (level) {
            case "RED" -> 2;
            case "AMBER" -> 1;
            default -> 0;
        };
    }
}
