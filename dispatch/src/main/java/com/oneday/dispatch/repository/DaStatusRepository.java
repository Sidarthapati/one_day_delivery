package com.oneday.dispatch.repository;

import com.oneday.dispatch.domain.DaStatus;
import com.oneday.dispatch.domain.DaStatusEnum;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DaStatusRepository extends JpaRepository<DaStatus, UUID> {

    /** Current status row for a DA (one per DA). */
    Optional<DaStatus> findByDaId(UUID daId);

    /** City roster for a shift filtered by status (absent detection, station view). */
    List<DaStatus> findByCityIdAndShiftDateAndStatusIn(
            UUID cityId, LocalDate shiftDate, Collection<DaStatusEnum> statuses);

    /** All-city roster for a shift filtered by status (ADMIN, unscoped). */
    List<DaStatus> findByShiftDateAndStatusIn(LocalDate shiftDate, Collection<DaStatusEnum> statuses);

    /** Every DA rostered to one shift on a date (any status) — the shift scope for an absence split. */
    List<DaStatus> findByCityIdAndShiftDateAndShiftType(UUID cityId, LocalDate shiftDate, String shiftType);

    /** City roster for one shift-type filtered by status (shift-scoped absence picker). */
    List<DaStatus> findByCityIdAndShiftDateAndShiftTypeAndStatusIn(
            UUID cityId, LocalDate shiftDate, String shiftType, Collection<DaStatusEnum> statuses);

    /** All-city roster for one shift-type filtered by status (ADMIN, shift-scoped picker). */
    List<DaStatus> findByShiftDateAndShiftTypeAndStatusIn(
            LocalDate shiftDate, String shiftType, Collection<DaStatusEnum> statuses);
}
