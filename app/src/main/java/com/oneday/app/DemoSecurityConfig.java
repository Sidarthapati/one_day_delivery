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

/**
 * Local-dev / demo security override.
 * Opens /api/** and /internal/** without JWT so the demo website can call the backend directly.
 * Never active in prod (guarded by @Profile("!prod")).
 *
 * JwtAuthenticationFilter is declared as a @Bean in auth's SecurityConfig, which causes Spring Boot
 * to auto-register it as a servlet filter for ALL requests. We disable that auto-registration here
 * so it only runs within the auth module's own SecurityFilterChain (where it's added via addFilterBefore).
 */
@Configuration
@Profile("!prod")
class DemoSecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain demoApiChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/api/**", "/internal/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
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
