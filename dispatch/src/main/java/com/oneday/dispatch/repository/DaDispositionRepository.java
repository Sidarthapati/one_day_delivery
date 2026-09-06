package com.oneday.dispatch.repository;

import com.oneday.dispatch.domain.DaDisposition;
import com.oneday.dispatch.domain.DispositionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DaDispositionRepository extends JpaRepository<DaDisposition, UUID> {

    /** The DA's live disposition for a day, if any — the active/double-request guard (date-scoped so a
     *  stale row from a prior day never blocks a new request). */
    Optional<DaDisposition> findFirstByDaIdAndOperatingDateAndStatusIn(
            UUID daId, LocalDate operatingDate, List<DispositionStatus> statuses);

    /** All of a DA's dispositions for a day (allowance sum + driver-app history). */
    List<DaDisposition> findByDaIdAndOperatingDate(UUID daId, LocalDate operatingDate);

    /** Every ACTIVE disposition on a day — the overstay/reconcile sweep set. */
    List<DaDisposition> findByOperatingDateAndStatus(LocalDate operatingDate, DispositionStatus status);

    /** Live rows carried over from a prior day — the sweep auto-cancels these so they can't lock a DA. */
    List<DaDisposition> findByOperatingDateBeforeAndStatusIn(
            LocalDate operatingDate, List<DispositionStatus> statuses);

    /** A city's dispositions in the given statuses for a day — the manager console view. */
    List<DaDisposition> findByCityIdAndOperatingDateAndStatusIn(
            UUID cityId, LocalDate operatingDate, List<DispositionStatus> statuses);
}
