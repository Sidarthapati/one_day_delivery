package com.oneday.dispatch.repository;

import com.oneday.dispatch.domain.DaDisposition;
import com.oneday.dispatch.domain.DispositionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DaDispositionRepository extends JpaRepository<DaDisposition, UUID> {

    /** The DA's live (PENDING or ACTIVE) disposition, if any — the active/double-request guard. */
    Optional<DaDisposition> findFirstByDaIdAndStatusIn(UUID daId, List<DispositionStatus> statuses);

    /** All of a DA's dispositions for a day (allowance sum + driver-app history). */
    List<DaDisposition> findByDaIdAndOperatingDate(UUID daId, LocalDate operatingDate);

    /** Every ACTIVE disposition on a day — the overstay/reconcile sweep set. */
    List<DaDisposition> findByOperatingDateAndStatus(LocalDate operatingDate, DispositionStatus status);

    /** A city's dispositions in the given statuses for a day — the manager console view. */
    List<DaDisposition> findByCityIdAndOperatingDateAndStatusIn(
            UUID cityId, LocalDate operatingDate, List<DispositionStatus> statuses);
}
