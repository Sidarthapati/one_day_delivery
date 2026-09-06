package com.oneday.dispatch.repository;

import com.oneday.dispatch.domain.DaLocationStub;
import com.oneday.dispatch.domain.StubStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The DA location-stub store. The per-task rollups are atomic {@code @Modifying} UPDATEs (mirroring
 * {@code orders.ParcelOrderRepository}) — LEAST/GREATEST keep the dwell endpoints monotonic without a
 * read-modify-write. All stub mutations for a DA run under the DA lock, so these never race within a DA.
 */
public interface DaLocationStubRepository extends JpaRepository<DaLocationStub, UUID> {

    /** The OPEN visit for a (DA, day, location) to join, or empty → the caller opens a new stub. */
    Optional<DaLocationStub> findFirstByDaIdAndOperatingDateAndLocationKeyAndStatus(
            UUID daId, LocalDate operatingDate, String locationKey, StubStatus status);

    /** A DA's visits for a day, chronological (app + station DA-detail dwell view). */
    List<DaLocationStub> findByDaIdAndOperatingDateOrderByOpenedAtAsc(UUID daId, LocalDate operatingDate);

    /** Fold a newly-assigned task into the visit's ticket size. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE da_location_stub SET task_count = task_count + 1 WHERE id = :id", nativeQuery = true)
    int incrementTaskCount(@Param("id") UUID id);

    /** Start the dwell clock: the earliest "Mark arrived" across the visit's tasks (write-once via LEAST). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE da_location_stub "
            + "SET first_arrived_at = LEAST(COALESCE(first_arrived_at, :ts), :ts) WHERE id = :id",
            nativeQuery = true)
    int recordArrival(@Param("id") UUID id, @Param("ts") Instant ts);

    /** A task completed: advance the dwell-clock end and the processed count. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE da_location_stub "
            + "SET last_completed_at = GREATEST(COALESCE(last_completed_at, :ts), :ts), "
            + "processed_count = processed_count + 1 WHERE id = :id",
            nativeQuery = true)
    int recordProcessed(@Param("id") UUID id, @Param("ts") Instant ts);

    /** A task failed: it still ends the DA's time at the location, so advance the dwell-clock end + fail count. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE da_location_stub "
            + "SET last_completed_at = GREATEST(COALESCE(last_completed_at, :ts), :ts), "
            + "failed_count = failed_count + 1 WHERE id = :id",
            nativeQuery = true)
    int recordFailed(@Param("id") UUID id, @Param("ts") Instant ts);
}
