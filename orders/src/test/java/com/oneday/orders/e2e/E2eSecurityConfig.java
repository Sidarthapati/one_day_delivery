package com.oneday.orders.e2e;

import com.oneday.auth.repository.ApiKeyRepository;
import com.oneday.auth.security.JwtAuthenticationFilter;
import com.oneday.auth.service.AuthService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Test security chain that mirrors production's {@code DemoSecurityConfig}: the real M1
 * {@link JwtAuthenticationFilter} runs (parsing the {@code Authorization} header into the
 * principal), but every request is permitted at the security layer so the per-endpoint role
 * gates in the controllers are what actually enforce authorization — exactly as in the live app.
 * {@code AuthService}/{@code ApiKeyRepository} are mocked beans (JWT crypto is M1's concern).
 */
@TestConfiguration
public class E2eSecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain e2eSecurityChain(HttpSecurity http,
                                         AuthService authService,
                                         ApiKeyRepository apiKeyRepository) throws Exception {
        return http
                .securityMatcher("/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .addFilterBefore(new JwtAuthenticationFilter(authService, apiKeyRepository),
                        UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
