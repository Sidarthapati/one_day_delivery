package com.oneday.exceptions.service.impl;

import com.oneday.common.kafka.events.DaLifecycleEvent;
import com.oneday.common.port.DaDirectoryPort;
import com.oneday.exceptions.domain.AttendanceAlert;
import com.oneday.exceptions.domain.AttendanceAlertStatus;
import com.oneday.exceptions.domain.AttendanceResolution;
import com.oneday.exceptions.dto.AttendanceAlertResponse;
import com.oneday.exceptions.repository.AttendanceAlertRepository;
import com.oneday.exceptions.service.AttendanceAlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Opens/closes attendance alerts from M5's {@code ATTENDANCE_*} events. The unconfirmed event carries
 * {@code reasonCode = "{SHIFT}|{cityCode}"}; the resolved event carries {@code PRESENT|ABSENT}. Dedupe
 * is by the {@code (da_id, attendance_date)} unique constraint.
 */
@Service
class AttendanceAlertServiceImpl implements AttendanceAlertService {

    private static final Logger log = LoggerFactory.getLogger(AttendanceAlertServiceImpl.class);

    private final AttendanceAlertRepository repository;
    private final DaDirectoryPort daDirectory;

    AttendanceAlertServiceImpl(AttendanceAlertRepository repository, DaDirectoryPort daDirectory) {
        this.repository = repository;
        this.daDirectory = daDirectory;
    }

    @Override
    @Transactional
    public void raise(DaLifecycleEvent event) {
        UUID daId = event.daId();
        LocalDate date = event.validDate();
        if (daId == null || date == null) {
            return;
        }
        if (repository.findByDaIdAndAttendanceDate(daId, date).isPresent()) {
            return; // already raised (or already resolved) for this DA/day
        }
        String[] parts = splitReason(event.reasonCode());
        String shift = parts[0];
        String cityCode = parts[1];

        AttendanceAlert alert = new AttendanceAlert();
        alert.setDaId(daId);
        alert.setCityId(event.cityId());
        alert.setCityCode(cityCode);
        alert.setAttendanceDate(date);
        alert.setShiftType(shift);
        alert.setDaName(nameFor(daId));
        alert.setStatus(AttendanceAlertStatus.OPEN);
        try {
            repository.save(alert);
        } catch (DataIntegrityViolationException concurrent) {
            log.debug("Attendance alert for DA {} on {} already exists", daId, date);
        }
    }

    @Override
    @Transactional
    public void resolve(DaLifecycleEvent event) {
        UUID daId = event.daId();
        LocalDate date = event.validDate();
        if (daId == null || date == null) {
            return;
        }
        Optional<AttendanceAlert> found = repository.findByDaIdAndAttendanceDate(daId, date);
        if (found.isEmpty()) {
            return; // resolved before any alert was raised (manager acted before cutoff) — nothing to close
        }
        AttendanceAlert alert = found.get();
        if (alert.getStatus() == AttendanceAlertStatus.RESOLVED) {
            return;
        }
        alert.setStatus(AttendanceAlertStatus.RESOLVED);
        alert.setResolution("ABSENT".equals(event.reasonCode())
                ? AttendanceResolution.ABSENT : AttendanceResolution.PRESENT);
        alert.setResolvedAt(Instant.now());
        repository.save(alert);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AttendanceAlertResponse> openAlerts(String cityScope, LocalDate date) {
        List<AttendanceAlert> alerts;
        if (cityScope == null) {
            alerts = repository.findByStatusAndAttendanceDateOrderByCreatedAtDesc(AttendanceAlertStatus.OPEN, date);
        } else {
            // A station manager's city_id claim can be either the grid code ("delhi") or the city UUID.
            // Alerts carry both columns, so scope by whichever shape the claim is.
            UUID cityId = tryUuid(cityScope);
            alerts = cityId != null
                    ? repository.findByStatusAndAttendanceDateAndCityIdOrderByCreatedAtDesc(
                            AttendanceAlertStatus.OPEN, date, cityId)
                    : repository.findByStatusAndAttendanceDateAndCityCodeOrderByCreatedAtDesc(
                            AttendanceAlertStatus.OPEN, date, cityScope);
        }
        return alerts.stream().map(this::toResponse).toList();
    }

    private AttendanceAlertResponse toResponse(AttendanceAlert a) {
        return new AttendanceAlertResponse(a.getId(), a.getDaId(), a.getDaName(), a.getCityId(),
                a.getCityCode(), a.getAttendanceDate(), a.getShiftType(), a.getStatus().name(),
                a.getResolution() != null ? a.getResolution().name() : null, a.getCreatedAt());
    }

    /** The scope as a UUID if it parses as one (city UUID claim), else null (a grid code like "delhi"). */
    private static UUID tryUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException notUuid) {
            return null;
        }
    }

    private String nameFor(UUID daId) {
        Map<UUID, DaDirectoryPort.DaContact> contacts = daDirectory.contactsFor(List.of(daId));
        DaDirectoryPort.DaContact c = contacts.get(daId);
        return c != null ? c.name() : null;
    }

    /** reasonCode = "{SHIFT}|{cityCode}"; either side may be missing. Returns [shift, cityCode]. */
    private String[] splitReason(String reasonCode) {
        if (reasonCode == null) {
            return new String[]{null, null};
        }
        int bar = reasonCode.indexOf('|');
        if (bar < 0) {
            return new String[]{reasonCode, null};
        }
        String shift = reasonCode.substring(0, bar);
        String city = reasonCode.substring(bar + 1);
        return new String[]{shift.isBlank() ? null : shift, city.isBlank() ? null : city};
    }
}
