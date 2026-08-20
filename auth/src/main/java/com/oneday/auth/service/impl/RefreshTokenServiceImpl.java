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
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
class RefreshTokenServiceImpl implements RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenServiceImpl.class);
    private static final int RAW_TOKEN_BYTES = 32;

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
        RefreshToken current = repository.findByTokenHash(sha256Hex(rawToken))
                .orElseThrow(() -> new InvalidRefreshTokenException("Unknown refresh token"));

        // Reuse detection: a token presented after it was already rotated/revoked signals theft —
        // revoke the whole lineage so neither the attacker nor the victim can keep using it.
        if (current.isRevoked()) {
            repository.revokeFamily(current.getFamilyId(), Instant.now());
            log.warn("Refresh-token reuse detected; revoked family {}", current.getFamilyId());
            throw new InvalidRefreshTokenException("Refresh token already used");
        }
        if (current.isExpired()) {
            throw new InvalidRefreshTokenException("Refresh token expired");
        }

        // Re-load the user so an account deactivated after issue can't be refreshed back in.
        User user = userRepository.findActiveByIdWithRole(current.getUser().getId())
                .orElseThrow(() -> new InvalidRefreshTokenException("User not found or inactive"));

        Minted next = mint(user, current.getFamilyId());
        current.setRevokedAt(Instant.now());
        current.setReplacedById(next.entity().getId());
        repository.save(current);

        return new Rotation(user, next.rawToken(), next.entity().getExpiresAt());
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
