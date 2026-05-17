package com.oneday.auth.security;

import com.oneday.auth.repository.ApiKeyRepository;
import com.oneday.auth.service.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final AuthService authService;
    private final ApiKeyRepository apiKeyRepository;

    public JwtAuthenticationFilter(AuthService authService, ApiKeyRepository apiKeyRepository) {
        this.authService = authService;
        this.apiKeyRepository = apiKeyRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String apiKeyHeader = request.getHeader("X-Api-Key");
        if (apiKeyHeader != null) {
            tryAuthenticateWithApiKey(apiKeyHeader);
            chain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            tryAuthenticateWithJwt(authHeader.substring(7));
        }

        chain.doFilter(request, response);
    }

    private void tryAuthenticateWithApiKey(String rawKey) {
        try {
            String hash = sha256Hex(rawKey);
            apiKeyRepository.findActiveByKeyHashWithUser(hash).ifPresent(apiKey -> {
                var user = apiKey.getUser();
                if (!user.isActive())
                    return;
                apiKey.setLastUsedAt(Instant.now());
                apiKeyRepository.save(apiKey);
                setAuthentication(user);
            });
        } catch (Exception ignored) {
        }
    }

    private void tryAuthenticateWithJwt(String token) {
        try {
            var user = authService.validateToken(token);
            setAuthentication(user);
        } catch (Exception ignored) {
        }
    }

    private void setAuthentication(com.oneday.auth.domain.User user) {
        var details = new AuthUserDetails(user);
        var auth = new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static String sha256Hex(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
