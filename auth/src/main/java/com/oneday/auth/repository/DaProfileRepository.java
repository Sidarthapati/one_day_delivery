package com.oneday.auth.repository;

import com.oneday.auth.domain.DaProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface DaProfileRepository extends JpaRepository<DaProfile, UUID> {

    /**
     * The roster query: active DELIVERY_ASSOCIATE users in the city, on the requested shift, whose
     * contract covers {@code date}. Native so it can join {@code users}/{@code roles} across the
     * shared datasource without dragging the JPA graph in.
     */
    @Query(value = """
            SELECT u.id FROM users u
              JOIN da_profile p ON p.user_id = u.id
              JOIN roles r      ON r.id = u.role_id
             WHERE r.name = 'DELIVERY_ASSOCIATE'
               AND u.active = true
               AND u.city_id = :cityId
               AND p.shift = :shift
               AND p.contract_start_date <= :date
               AND (p.contract_end_date IS NULL OR p.contract_end_date >= :date)
            """, nativeQuery = true)
    List<UUID> findAvailableDaIds(@Param("cityId") String cityId,
                                  @Param("shift") String shift,
                                  @Param("date") LocalDate date);

    /** Admin list of registered DAs, optionally filtered by city / shift / active. */
    @Query(value = """
            SELECT u.id AS daId,
                   (p.first_name || ' ' || p.last_name) AS name,
                   u.email AS email,
                   u.phone AS phone,
                   u.city_id AS cityId,
                   p.shift AS shift,
                   p.contract_start_date AS contractStartDate,
                   p.contract_end_date AS contractEndDate,
                   u.active AS active
              FROM da_profile p
              JOIN users u ON u.id = p.user_id
             WHERE (:cityId IS NULL OR u.city_id = :cityId)
               AND (:shift  IS NULL OR p.shift = :shift)
               AND (:active IS NULL OR u.active = :active)
             ORDER BY p.created_at DESC
            """, nativeQuery = true)
    List<DaSummary> findDaSummaries(@Param("cityId") String cityId,
                                    @Param("shift") String shift,
                                    @Param("active") Boolean active);

    /** Native projection for the admin list. */
    interface DaSummary {
        UUID getDaId();
        String getName();
        String getEmail();
        String getPhone();
        String getCityId();
        String getShift();
        LocalDate getContractStartDate();
        LocalDate getContractEndDate();
        boolean getActive();
    }
}
