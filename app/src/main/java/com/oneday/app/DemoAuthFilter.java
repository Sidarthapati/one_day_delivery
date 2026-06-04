package com.oneday.app;

import com.oneday.auth.domain.Role;
import com.oneday.auth.domain.User;
import com.oneday.auth.security.AuthUserDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.UUID;

/**
 * Demo-only servlet filter (active outside prod) that populates the SecurityContext with a
 * synthetic AuthUserDetails principal so IdempotencyFilter can extract a userId without JWT.
 * Runs at high precedence (-100) to guarantee it executes before IdempotencyFilter.
 */
@Component
@Profile("!prod")
@Order(-99)
class DemoAuthFilter extends OncePerRequestFilter {

    static final UUID DEMO_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final AuthUserDetails DEMO_PRINCIPAL = buildDemoPrincipal();

    private static AuthUserDetails buildDemoPrincipal() {
        try {
            Role role = new Role();
            role.setName("CUSTOMER");
            role.setDisplayName("Customer");
            role.setActive(true);
            setBaseId(role, UUID.fromString("00000000-0000-0000-0000-000000000002"));

            User user = new User();
            user.setEmail("demo@oneday.local");
            user.setPasswordHash("demo");
            user.setName("Demo User");
            user.setActive(true);
            user.setRole(role);
            setBaseId(user, DEMO_USER_ID);

            return new AuthUserDetails(user);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build demo principal", e);
        }
    }

    private static void setBaseId(Object entity, UUID id) throws Exception {
        Field field = entity.getClass().getSuperclass().getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // Replace any non-AuthUserDetails principal (e.g. AnonymousAuthenticationToken set by
        // AnonymousAuthenticationFilter inside Spring Security's chain) with the demo principal.
        // This must run after the security filter chain so that SecurityContextHolderFilter has
        // already set the context — but we override the anonymous token before IdempotencyFilter runs.
        Authentication existing = SecurityContextHolder.getContext().getAuthentication();
        if (existing == null || !(existing.getPrincipal() instanceof AuthUserDetails)) {
            UsernamePasswordAuthenticationToken token =
                    new UsernamePasswordAuthenticationToken(
                            DEMO_PRINCIPAL, null, DEMO_PRINCIPAL.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(token);
        }
        chain.doFilter(request, response);
    }
}
