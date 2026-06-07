package com.oneday.app;

import com.oneday.auth.security.JwtAuthenticationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Local-dev / demo security override.
 * Opens /api/** and /internal/** (permitAll, no CSRF) so the demo website can call the backend
 * directly. Never active in prod (guarded by @Profile("!prod")).
 *
 * <p>The chain still runs {@link JwtAuthenticationFilter}, so a request that carries a real
 * {@code Authorization: Bearer <jwt>} is authenticated as its actual user/role (a customer can
 * book; an ADMIN cannot — see {@code Authz#requireCustomerRole}). A request with no token is left
 * unauthenticated here and {@code DemoAuthFilter} then fills in the synthetic ADMIN principal.
 * permitAll means a missing/invalid token is never rejected at the security layer — per-endpoint
 * role checks live in the controllers.</p>
 *
 * <p>JwtAuthenticationFilter is declared as a @Bean in auth's SecurityConfig, which would make Spring
 * Boot auto-register it as a servlet filter for ALL requests; we disable that global auto-registration
 * so it runs only within explicit chains (this one and the auth module's own).</p>
 */
@Configuration
@Profile("!prod")
class DemoSecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain demoApiChain(HttpSecurity http,
                                     JwtAuthenticationFilter jwtAuthenticationFilter) throws Exception {
        return http
                .securityMatcher("/api/**", "/internal/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                // Stateless API: auth is via Authorization/X-Api-Key headers, not cookies — no CSRF surface.
                // lgtm[java/spring-disabled-csrf-protection]
                .csrf(AbstractHttpConfigurer::disable)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    FilterRegistrationBean<JwtAuthenticationFilter> disableJwtAutoRegistration(
            JwtAuthenticationFilter jwtFilter) {
        FilterRegistrationBean<JwtAuthenticationFilter> reg = new FilterRegistrationBean<>(jwtFilter);
        reg.setEnabled(false);
        return reg;
    }
}
