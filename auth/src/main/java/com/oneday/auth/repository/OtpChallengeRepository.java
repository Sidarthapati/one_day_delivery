package com.oneday.auth.repository;

import com.oneday.auth.domain.OtpChallenge;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OtpChallengeRepository extends JpaRepository<OtpChallenge, UUID> {

    /** The most recent unconsumed challenge for a phone (there is at most one after requestOtp). */
    Optional<OtpChallenge> findTopByPhoneAndConsumedFalseOrderByCreatedAtDesc(String phone);

    /** Invalidate any prior challenges for a phone before issuing a fresh one. */
    void deleteByPhone(String phone);
}
