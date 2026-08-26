package com.oneday.dispatch.service.impl;

import com.oneday.common.domain.Shift;
import com.oneday.common.port.DaDirectoryPort;
import com.oneday.dispatch.config.DispatchProperties;
import com.oneday.dispatch.domain.DaAttendance;
import com.oneday.dispatch.domain.DaAttendanceMethod;
import com.oneday.dispatch.domain.DaAttendanceStatus;
import com.oneday.dispatch.domain.DaGpsPing;
import com.oneday.dispatch.domain.DaStatus;
import com.oneday.dispatch.dto.response.AbsencePreviewResponse;
import com.oneday.dispatch.dto.response.AttendanceMusterEntry;
import com.oneday.dispatch.events.DaEventProducer;
import com.oneday.dispatch.repository.DaAttendanceRepository;
import com.oneday.dispatch.repository.DaGpsPingRepository;
import com.oneday.dispatch.repository.DaStatusRepository;
import com.oneday.dispatch.service.AbsenceReassignmentService;
import com.oneday.dispatch.service.AttendanceService;
import com.oneday.grid.service.GridService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Geocoded attendance implementation. Presence is derived from proximity to the DA's city hub
 * ({@link GeoDistance} vs {@code dispatch.attendance.hub-locations}). The reactive path
 * ({@link #onGpsFix}) is guarded by an in-memory per-day set so the hot ping path stays cheap; the DB
 * is touched only on the first near-hub fix. Manager overrides emit {@code ATTENDANCE_RESOLVED} so M11
 * can close the alert, and marking absent triggers {@link AbsenceReassignmentService}.
 */
@Service
class AttendanceServiceImpl implements AttendanceService {

    private static final Logger log = LoggerFactory.getLogger(AttendanceServiceImpl.class);

    private final DaAttendanceRepository attendanceRepository;
    private final DaStatusRepository daStatusRepository;
    private final DaGpsPingRepository daGpsPingRepository;
    private final DaDirectoryPort daDirectory;
    private final AbsenceReassignmentService absenceService;
    private final DaEventProducer eventProducer;
    private final GridService gridService;
    private final DispatchProperties props;

    /** daId → date it was already marked present, so repeat pings short-circuit before any DB read. */
    private final Map<UUID, LocalDate> markedPresentOn = new ConcurrentHashMap<>();

    AttendanceServiceImpl(DaAttendanceRepository attendanceRepository, DaStatusRepository daStatusRepository,
                          DaGpsPingRepository daGpsPingRepository, DaDirectoryPort daDirectory,
                          AbsenceReassignmentService absenceService, DaEventProducer eventProducer,
                          GridService gridService, DispatchProperties props) {
        this.attendanceRepository = attendanceRepository;
        this.daStatusRepository = daStatusRepository;
        this.daGpsPingRepository = daGpsPingRepository;
        this.daDirectory = daDirectory;
        this.absenceService = absenceService;
        this.eventProducer = eventProducer;
        this.gridService = gridService;
        this.props = props;
    }

    private ZoneId zone() {
        return ZoneId.of(props.getAttendance().getZone());
    }

    @Override
    @Transactional
    public void onGpsFix(UUID daId, UUID cityId, String shiftType, double lat, double lon, Instant pingAt) {
        LocalDate today = LocalDate.now(zone());
        if (today.equals(markedPresentOn.get(daId))) {
            return; // already present today — skip without a DB read
        }
        DispatchProperties.Attendance.HubLocation hub = hubFor(cityId);
        if (hub == null) {
            return; // no hub geofence configured for this city
        }
        double dist = GeoDistance.meters(lat, lon, hub.getLat(), hub.getLon());
        if (dist > props.getAttendance().getRadiusMeters()) {
            return; // not near the hub — presence not established by this fix
        }
        // Within the geofence: ensure a row exists (respect a manager's prior override — insert only).
        if (attendanceRepository.findByDaIdAndAttendanceDate(daId, today).isPresent()) {
            markedPresentOn.put(daId, today);
            return;
        }
        DaAttendance row = new DaAttendance();
        row.setDaId(daId);
        row.setCityId(cityId);
        row.setAttendanceDate(today);
        row.setShiftType(shiftType);
        row.setStatus(DaAttendanceStatus.PRESENT);
        row.setMethod(DaAttendanceMethod.AUTO_GEOFENCE);
        row.setDetectedLat(lat);
        row.setDetectedLon(lon);
        row.setDistanceM(dist);
        row.setSourcePingAt(pingAt);
        try {
            attendanceRepository.save(row);
        } catch (DataIntegrityViolationException concurrent) {
            // A concurrent ping won the unique (da_id, attendance_date) race — fine, already present.
            log.debug("Attendance row for DA {} on {} already created concurrently", daId, today);
        }
        markedPresentOn.put(daId, today);
    }

    @Override
    @Transactional
    public AttendanceMusterEntry checkIn(UUID daId, Double lat, Double lon) {
        LocalDate today = LocalDate.now(zone());

        // Already settled for the day (auto-present, a prior tap, or a manager override) — idempotent:
        // return it without re-checking the geofence, so a second tap after leaving the hub never 422s.
        Optional<DaAttendance> settled = attendanceRepository.findByDaIdAndAttendanceDate(daId, today);
        if (settled.isPresent()) {
            markedPresentOn.put(daId, today);
            return toEntry(daId, null, settled.get());
        }

        DaStatus da = daStatusRepository.findByDaId(daId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "DA is not on shift"));

        double useLat, useLon;
        Instant pingAt;
        if (lat != null && lon != null) {
            useLat = lat;
            useLon = lon;
            pingAt = Instant.now();
        } else {
            DaGpsPing last = daGpsPingRepository.findTopByDaIdOrderByRecordedAtDesc(daId);
            if (last == null) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "No location available — enable GPS and try again");
            }
            useLat = last.getLat();
            useLon = last.getLon();
            pingAt = last.getRecordedAt();
        }

        DispatchProperties.Attendance.HubLocation hub = hubFor(da.getCityId());
        if (hub == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "No hub configured for your city");
        }
        double dist = GeoDistance.meters(useLat, useLon, hub.getLat(), hub.getLon());
        if (dist > props.getAttendance().getRadiusMeters()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "You are " + Math.round(dist) + " m from the hub — move within "
                            + props.getAttendance().getRadiusMeters() + " m to check in");
        }

        // No row yet for today (the settled short-circuit above ruled that out) — record the check-in.
        DaAttendance row = new DaAttendance();
        row.setDaId(daId);
        row.setCityId(da.getCityId());
        row.setAttendanceDate(today);
        row.setShiftType(da.getShiftType());
        row.setStatus(DaAttendanceStatus.PRESENT);
        row.setMethod(DaAttendanceMethod.MANUAL_CHECKIN);
        row.setDetectedLat(useLat);
        row.setDetectedLon(useLon);
        row.setDistanceM(dist);
        row.setSourcePingAt(pingAt);
        attendanceRepository.save(row);
        markedPresentOn.put(daId, today);
        return toEntry(daId, null, row);
    }

    @Override
    @Transactional(readOnly = true)
    public AttendanceMusterEntry today(UUID daId) {
        LocalDate today = LocalDate.now(zone());
        DaAttendance row = attendanceRepository.findByDaIdAndAttendanceDate(daId, today).orElse(null);
        return toEntry(daId, null, row); // row == null → PENDING
    }

    @Override
    @Transactional(readOnly = true)
    public List<AttendanceMusterEntry> muster(String cityCode, LocalDate date, Shift shift) {
        UUID cityId = gridService.resolveCityId(cityCode);
        List<UUID> expected = daDirectory.availableDaIds(cityCode, date, shift);
        Map<UUID, DaDirectoryPort.DaContact> names = daDirectory.contactsFor(expected);
        Map<UUID, DaAttendance> rowByDa = new java.util.HashMap<>();
        for (DaAttendance r : attendanceRepository
                .findByCityIdAndAttendanceDateAndShiftType(cityId, date, shift.name())) {
            rowByDa.put(r.getDaId(), r);
        }
        return expected.stream().map(daId -> {
            DaDirectoryPort.DaContact c = names.get(daId);
            return toEntry(daId, c != null ? c.name() : null, rowByDa.get(daId));
        }).toList();
    }

    @Override
    @Transactional
    public void markPresent(UUID daId, LocalDate date, UUID actorUserId, UUID scopeCityId) {
        DaStatus da = requireDaInScope(daId, scopeCityId);
        upsertOverride(daId, da, date, DaAttendanceStatus.PRESENT, DaAttendanceMethod.MANAGER_PRESENT, actorUserId);
        markedPresentOn.put(daId, date);
        eventProducer.emitAttendanceResolved(daId, da.getCityId(), date, true);
    }

    @Override
    @Transactional
    public AbsencePreviewResponse markAbsent(UUID daId, LocalDate date, String reason,
                                             UUID actorUserId, UUID scopeCityId) {
        DaStatus da = requireDaInScope(daId, scopeCityId);
        upsertOverride(daId, da, date, DaAttendanceStatus.ABSENT, DaAttendanceMethod.MANAGER_ABSENT, actorUserId);
        AbsencePreviewResponse preview =
                absenceService.preview(da.getCityId(), List.of(daId), reason, actorUserId);
        eventProducer.emitAttendanceResolved(daId, da.getCityId(), date, false);
        return preview;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private DaStatus requireDaInScope(UUID daId, UUID scopeCityId) {
        DaStatus da = daStatusRepository.findByDaId(daId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "DA is not on shift"));
        if (scopeCityId != null && !scopeCityId.equals(da.getCityId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot act on a DA outside your city");
        }
        return da;
    }

    private void upsertOverride(UUID daId, DaStatus da, LocalDate date, DaAttendanceStatus status,
                                DaAttendanceMethod method, UUID actorUserId) {
        DaAttendance row = attendanceRepository.findByDaIdAndAttendanceDate(daId, date)
                .orElseGet(DaAttendance::new);
        row.setDaId(daId);
        row.setCityId(da.getCityId());
        row.setAttendanceDate(date);
        row.setShiftType(da.getShiftType());
        row.setStatus(status);
        row.setMethod(method);
        row.setMarkedByUserId(actorUserId);
        attendanceRepository.save(row);
    }

    private DispatchProperties.Attendance.HubLocation hubFor(UUID cityId) {
        return cityId == null ? null : props.getAttendance().getHubLocations().get(cityId.toString());
    }

    private AttendanceMusterEntry toEntry(UUID daId, String name, DaAttendance row) {
        if (row == null) {
            return new AttendanceMusterEntry(daId, name, null, "PENDING", null, null, null);
        }
        return new AttendanceMusterEntry(daId, name, row.getShiftType(), row.getStatus().name(),
                row.getMethod().name(), row.getDistanceM(), row.getCreatedAt());
    }
}
