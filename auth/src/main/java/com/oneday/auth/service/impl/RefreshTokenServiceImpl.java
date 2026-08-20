package com.oneday.auth.service.impl;

import com.oneday.auth.config.RefreshTokenProperties;
import com.oneday.auth.domain.RefreshToken;
import com.oneday.auth.domain.User;
import com.oneday.auth.exception.InvalidRefreshTokenException;
import com.oneday.auth.repository.RefreshTokenRepository;
import com.oneday.auth.repository.UserRepository;
import com.oneday.auth.service.RefreshTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
class RefreshTokenServiceImpl implements RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenServiceImpl.class);
    private static final int RAW_TOKEN_BYTES = 32;

    // A lost atomic claim within this window of the token's revocation is treated as a benign
    // concurrent double-submit (two tabs), not theft — so the family is not revoked. A replay beyond
    // it is a stolen-token replay. Real reuse happens much later than a few seconds; a race is sub-ms.
    private static final Duration REUSE_GRACE = Duration.ofSeconds(30);

    private final RefreshTokenRepository repository;
    private final UserRepository userRepository;
    private final RefreshTokenProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    RefreshTokenServiceImpl(RefreshTokenRepository repository,
            UserRepository userRepository,
            RefreshTokenProperties properties) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.properties = properties;
    }

    @Override
    @Transactional
    public Issued issue(User user) {
        Minted minted = mint(user, UUID.randomUUID());
        return new Issued(minted.rawToken(), minted.entity().getExpiresAt());
    }

    @Override
    @Transactional
    public Rotation rotate(String rawToken) {
        String hash = sha256Hex(rawToken);
        RefreshToken current = repository.findByTokenHash(hash)
                .orElseThrow(() -> new InvalidRefreshTokenException("Unknown refresh token"));

        if (!current.isRevoked() && current.isExpired()) {
            throw new InvalidRefreshTokenException("Refresh token expired");
        }

        // Atomically claim the token: the conditional UPDATE revokes it only if still active, so of N
        // concurrent /auth/refresh calls with the same token exactly one wins (rows == 1). This is
        // what guarantees a refresh token is used at most once under concurrency (finding #1).
        Instant now = Instant.now();
        boolean won = repository.revokeIfActive(hash, now) == 1;
        if (!won) {
            handleLostClaim(hash, now);
            throw new InvalidRefreshTokenException("Refresh token already used");
        }

        // We won — re-load the user so an account deactivated after issue can't be refreshed back in.
        User user = userRepository.findActiveByIdWithRole(current.getUser().getId())
                .orElseThrow(() -> new InvalidRefreshTokenException("User not found or inactive"));

        Minted next = mint(user, current.getFamilyId());
        // The bulk claim already set revoked_at in the DB; set it on the managed entity too (so the
        // flush doesn't reset it) and record the rotation lineage.
        current.setRevokedAt(now);
        current.setReplacedById(next.entity().getId());
        repository.save(current);

        return new Rotation(user, next.rawToken(), next.entity().getExpiresAt());
    }

    // Lost the atomic claim → the token was already revoked. Distinguish theft from a benign race by
    // how long ago it was revoked: a token replayed well after it was rotated is a stolen-token replay
    // → revoke the whole family; a near-simultaneous double-submit (e.g. two browser tabs) is benign
    // and must NOT log the user out everywhere.
    private void handleLostClaim(String hash, Instant now) {
        RefreshToken token = repository.findByTokenHash(hash).orElse(null);
        if (token == null) {
            return;
        }
        Instant revokedAt = token.getRevokedAt() != null ? token.getRevokedAt() : now;
        if (Duration.between(revokedAt, now).compareTo(REUSE_GRACE) > 0) {
            repository.revokeFamily(token.getFamilyId(), now);
            log.warn("Refresh-token reuse detected; revoked family {}", token.getFamilyId());
        }
    }

    @Override
    @Transactional
    public void revoke(String rawToken) {
        repository.findByTokenHash(sha256Hex(rawToken)).ifPresent(t -> {
            if (!t.isRevoked()) {
                t.setRevokedAt(Instant.now());
                repository.save(t);
            }
        });
    }

    private Minted mint(User user, UUID familyId) {
        byte[] bytes = new byte[RAW_TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        RefreshToken entity = new RefreshToken();
        entity.setTokenHash(sha256Hex(rawToken));
        entity.setUser(user);
        entity.setFamilyId(familyId);
        entity.setExpiresAt(Instant.now().plus(properties.getTtl()));
        entity = repository.save(entity); // flush to populate the generated id for replacedById

        return new Minted(entity, rawToken);
    }

    private record Minted(RefreshToken entity, String rawToken) {}

    private static String sha256Hex(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
