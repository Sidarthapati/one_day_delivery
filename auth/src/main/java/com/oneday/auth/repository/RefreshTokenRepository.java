package com.oneday.auth.repository;

import com.oneday.auth.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Atomically claim a token for rotation: revoke it <em>only if it is still active</em>. Returns
     * the number of rows updated — exactly one caller of N concurrent {@code /auth/refresh} requests
     * with the same token gets {@code 1} (the winner); the rest get {@code 0}. This is what makes the
     * "a refresh token is used at most once" invariant hold under concurrency.
     */
    @Modifying
    @Transactional
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now "
            + "WHERE t.tokenHash = :hash AND t.revokedAt IS NULL")
    int revokeIfActive(@Param("hash") String tokenHash, @Param("now") Instant now);

    /** Revoke every still-active token in a family — the theft response on reuse detection. */
    @Modifying
    @Transactional
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now "
            + "WHERE t.familyId = :familyId AND t.revokedAt IS NULL")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);

    /**
     * Delete tokens already past their expiry. Revoked-but-unexpired rows are kept until they'd
     * expire anyway, so reuse detection still fires for a stolen token within its natural lifetime.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM RefreshToken t WHERE t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
