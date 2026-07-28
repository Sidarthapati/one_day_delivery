package com.oneday.auth.security;

import com.oneday.auth.repository.ApiKeyRepository;
import com.oneday.auth.service.AuthService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(AuthService authService,
            ApiKeyRepository apiKeyRepository) {
        return new JwtAuthenticationFilter(authService, apiKeyRepository);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter) throws Exception {
        return http
                // Stateless API: auth is via Authorization/X-Api-Key headers, not cookies. Keep CSRF
                // enabled but ignore all endpoints (equivalent here, but no blanket disable).
                .csrf(csrf -> csrf.ignoringRequestMatchers("/**"))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(
                                (req, res, ex) -> res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")))
                .authorizeHttpRequests(auth -> auth
                        // CORS preflight never carries the Authorization header, so a protected route
                        // would otherwise 401 the OPTIONS request itself before the browser ever sends
                        // the real one. Permitting OPTIONS doesn't loosen anything — the actual request
                        // still goes through full authentication/authorization right below.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/auth/login", "/auth/register", "/auth/health", "/auth/request-onboarding").permitAll()
                        .requestMatchers("/", "/index.html", "/*.js", "/*.css", "/css/**", "/js/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
