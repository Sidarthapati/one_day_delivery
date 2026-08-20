package com.oneday.auth.service.impl;

import com.oneday.auth.domain.User;
import com.oneday.auth.service.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

@Service
class JwtServiceImpl implements JwtService {

    private final SecretKey key;
    private final long accessTtlSeconds;

    JwtServiceImpl(@Value("${jwt.secret}") String secret,
                   @Value("${jwt.expiry-hours:8}") long expiryHours,
                   @Value("${jwt.access-token-minutes:0}") long accessMinutes) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        // Minutes granularity wins when set (prod cuts the access TTL to ~15m so a stolen JWT is
        // short-lived); otherwise fall back to the legacy hours setting (8h on staging).
        this.accessTtlSeconds = accessMinutes > 0 ? accessMinutes * 60 : expiryHours * 3600;
    }

    @Override
    public String createToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("role", user.getRole().getName())
                .claim("cityId", user.getCityId())
                .claim("name", user.getName())
                .claim("mustChangePassword", user.isMustChangePassword())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiryFor(user)))
                .signWith(key)
                .compact();
    }

    @Override
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    @Override
    public Instant expiryFor(User user) {
        return Instant.now().plusSeconds(accessTtlSeconds);
    }
}
