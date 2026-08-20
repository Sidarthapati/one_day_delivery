package com.oneday.auth.security;

import com.oneday.auth.repository.ApiKeyRepository;
import com.oneday.auth.service.AuthService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    // Comma-separated allowed CORS origin patterns. Staging/dev default = localhost + *.vercel.app
    // (application.yml); prod narrows to the pinned console origins (application-prod.yml).
    @Value("${godspeed.cors.allowed-origins:*}")
    private String allowedOrigins;

    private final boolean prod;

    public SecurityConfig(Environment env) {
        this.prod = env.acceptsProfiles(Profiles.of("prod"));
    }

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
    public RateLimitFilter rateLimitFilter(RateLimitProperties rateLimitProperties) {
        return new RateLimitFilter(rateLimitProperties);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            RateLimitFilter rateLimitFilter) throws Exception {
        return http
                // Browser clients (customer/hub/station web) call from other origins; enable CORS so
                // Security handles the preflight, then apply the source bean below across all paths.
                .cors(Customizer.withDefaults())
                // Security response headers. nosniff / frame-DENY / referrer-policy are always on
                // (safe for JSON APIs + the separate-origin consoles). HSTS + CSP are prod-only so
                // staging's demo static page (external map tiles) keeps working unchanged.
                .headers(h -> {
                    h.contentTypeOptions(Customizer.withDefaults());
                    h.frameOptions(f -> f.deny());
                    h.referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN));
                    if (prod) {
                        h.httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31536000));
                        h.contentSecurityPolicy(csp -> csp
                                .policyDirectives("default-src 'self'; frame-ancestors 'none'; object-src 'none'"));
                    }
                })
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
                        // Health probes are public (load balancer / uptime monitor); details are
                        // hidden from anonymous callers. All other /actuator/** stays authenticated.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // Business self-signup — public, like /auth/request-onboarding (runs KYC, files a PENDING request).
                        .requestMatchers("/auth/request-business-onboarding").permitAll()
                        .requestMatchers("/auth/oauth/google", "/auth/otp/request", "/auth/otp/verify").permitAll()
                        // Refresh/logout carry the refresh token in the body, not the (expired) access JWT.
                        .requestMatchers("/auth/refresh", "/auth/logout").permitAll()
                        // Public "Talk to sales" capture + white-label shipment tracking (token-scoped).
                        .requestMatchers(HttpMethod.POST, "/api/sales/leads").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/track/**").permitAll()
                        .requestMatchers("/", "/index.html", "/*.js", "/*.css", "/css/**", "/js/**").permitAll()
                        // Security disclosure (RFC 9116) — must be publicly fetchable.
                        .requestMatchers("/.well-known/**").permitAll()
                        // Permit the error dispatch (Spring Boot's default). The JWT filter is skipped
                        // on the ERROR dispatch, so without this any 404/500 on an authenticated
                        // endpoint would be masked as a misleading 401.
                        .requestMatchers("/error").permitAll()

                        // ── Role-scoped operational modules (M3/M6/M7/M8/M9) ─────────────────────────
                        // These modules carry NO in-controller authorization, so these path rules are the
                        // gate (previously any authenticated user — even a B2C customer — could approve
                        // route plans, seal hub bags, confirm airline handovers, etc.). Fine-grained
                        // "owns-this-resource" checks (van↔driver, da↔cron self) are a documented follow-up;
                        // this closes the privilege boundary. ADMIN is permitted everywhere.

                        // Customer booking map serviceability — must stay open to any authenticated user
                        // (restricting it to ops roles would break booking). Keep BEFORE the grid rules.
                        .requestMatchers(HttpMethod.GET, "/api/grid/serviceable-at").authenticated()

                        // M6 routing — van-driver app (role-gated; per-van ownership is a follow-up).
                        .requestMatchers("/api/v1/van/*/telemetry",
                                "/routing/vans/*/manifest", "/routing/vans/*/load-scan",
                                "/routing/vans/*/return-scan", "/routing/vans/*/stops/confirm",
                                "/routing/vans/*/breakdown").hasAnyRole("VAN_DRIVER", "ADMIN")
                        .requestMatchers("/routing/vans/*/recovery").hasAnyRole("STATION_MANAGER", "ADMIN")
                        .requestMatchers("/routing/vans/*/live").hasAnyRole("STATION_MANAGER", "SUPERVISOR", "ADMIN")
                        // Nightly route governance — mutations are ADMIN/STATION_MANAGER only.
                        .requestMatchers(HttpMethod.POST, "/routing/plans/*/approve",
                                "/routing/plans/*/override", "/routing/plans/*/replan").hasAnyRole("STATION_MANAGER", "ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/routing/fleet/**").hasAnyRole("STATION_MANAGER", "ADMIN")
                        .requestMatchers("/routing/cron/**").hasAnyRole("DELIVERY_ASSOCIATE", "STATION_MANAGER", "SUPERVISOR", "ADMIN")
                        // Planning-console reads (fleet GET, nodes, plans, shuttle timetable).
                        .requestMatchers("/routing/**").hasAnyRole("STATION_MANAGER", "SUPERVISOR", "ADMIN")

                        // M7 hub — operator console.
                        .requestMatchers("/hub/**").hasAnyRole("HUB_OPERATOR", "SUPERVISOR", "ADMIN")

                        // M3 grid — admin init, proposal governance, planning console.
                        .requestMatchers(HttpMethod.POST, "/api/grid/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/proposals/**").hasAnyRole("STATION_MANAGER", "ADMIN")
                        .requestMatchers("/api/grid/**").hasAnyRole("STATION_MANAGER", "SUPERVISOR", "ADMIN")

                        // M9 airline — ground-crew confirmations + admin. The WhatsApp AWB webhook is a
                        // stub needing its own Meta-signature auth (follow-up); role-gated until then.
                        .requestMatchers(HttpMethod.POST, "/airline/admin/**").hasRole("ADMIN")
                        .requestMatchers("/airline/**").hasAnyRole("AIRLINE_GHA", "ADMIN")

                        // M8 barcode — operational scan roles.
                        .requestMatchers("/api/v1/scan/**").hasAnyRole("HUB_OPERATOR", "DELIVERY_ASSOCIATE", "SHUTTLE_AGENT", "AIRLINE_GHA", "ADMIN")

                        // Orders stragglers: dev OTP peek (!prod) leaked cleartext OTP to any user;
                        // minting a gateway order is a customer action.
                        .requestMatchers("/internal/dev/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/payments/order").hasAnyRole("C2C_CUSTOMER", "B2C_CUSTOMER", "B2B_USER", "ADMIN")

                        // auth — role catalog disclosure (sibling POST/DELETE are already ADMIN-gated).
                        .requestMatchers(HttpMethod.GET, "/roles").hasAnyRole("ADMIN", "STATION_MANAGER")

                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // Throttle brute-force before any auth work happens.
                .addFilterBefore(rateLimitFilter, JwtAuthenticationFilter.class)
                .build();
    }

    /**
     * CORS for the web frontends. Auth is header-based (Authorization / X-Api-Key), never cookies,
     * so credentials stay off and any origin may call — the JWT is what authorizes, not the origin.
     * Applies to every path so {@code /auth/**} sign-in works from the browser too.
     *
     * <p>Allowed origins come from {@code godspeed.cors.allowed-origins} (comma-separated patterns):
     * staging/dev = localhost + {@code *.vercel.app}; prod = the pinned console origins. Patterns are
     * used (not exact origins) so {@code https://*.vercel.app} preview URLs still work in staging.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOriginPatterns(origins.isEmpty() ? List.of("*") : origins);
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setAllowCredentials(false);
        cfg.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }
}
