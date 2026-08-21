package com.oneday.auth.repository;

import com.oneday.auth.domain.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    Optional<User> findByPhone(String phone);

    Optional<User> findByProviderSubject(String providerSubject);

    @Query("SELECT u FROM User u JOIN FETCH u.role r WHERE u.id = :id AND u.active = true")
    Optional<User> findActiveByIdWithRole(@Param("id") UUID id);

    @Query("SELECT u FROM User u JOIN FETCH u.role r LEFT JOIN FETCH r.permissions WHERE u.id = :id")
    Optional<User> findByIdWithPermissions(@Param("id") UUID id);

    boolean existsByRoleId(UUID roleId);

    /**
     * A reachable on-duty staffer for a role, optionally scoped to a city — the "desk" contact for a
     * hub or GHA custody stage. Active, with a phone; {@code city} null matches any city.
     */
    @Query("""
            SELECT u FROM User u JOIN u.role r
            WHERE r.name = :role AND u.active = true AND u.phone IS NOT NULL
              AND (:city IS NULL OR u.cityId = :city)
            ORDER BY u.name
            """)
    List<User> findStaffByRoleAndCity(@Param("role") String role, @Param("city") String city, Pageable pageable);
}
