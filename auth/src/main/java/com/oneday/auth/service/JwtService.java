package com.oneday.auth.service;

import com.oneday.auth.domain.User;
import io.jsonwebtoken.Claims;

import java.time.Instant;

public interface JwtService {
    String createToken(User user);
    Claims parseToken(String token);
    Instant expiryFor(User user);
}
