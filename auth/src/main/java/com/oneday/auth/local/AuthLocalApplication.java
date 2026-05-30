package com.oneday.auth.local;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Standalone launcher for M1 (auth) only — boots the identity / JWT / role-management
 * slice on localhost without the rest of the monolith.
 *
 * <p>Why this exists: the only assembled artifact ({@code app/}) wires every module
 * together, and as of the M5-Implementation branch the grid (M3) Flyway migrations are
 * incomplete and {@code GridServiceImpl} runs an eager start-up query, so the full app
 * cannot boot locally. M1 auth, by contrast, is self-contained — it touches no Kafka and
 * no other module — so this runner brings it up on its own against the already-migrated
 * local {@code oneday} database.
 *
 * <p>Run it with:
 * <pre>mvn spring-boot:run -pl auth</pre>
 * The {@code authlocal} profile is activated automatically (see
 * {@code application-authlocal.properties}). Default admin seeded by
 * {@code DataInitializer}: {@code admin@oneday.in} / {@code Admin1234!}.
 *
 * <p>Lives in {@code com.oneday.auth.local} (a sibling of the test packages, not an
 * ancestor) so it never becomes the auto-detected {@code @SpringBootConfiguration} for
 * the {@code @WebMvcTest}/{@code @DataJpaTest} slices, which rely on {@code TestAuthApplication}.
 * Its component / entity / repository scans are pinned to {@code com.oneday.auth}, so the
 * common module's unconditional Kafka {@code EventPublisher} and the grid / orders beans
 * are never instantiated — no broker and no grid schema required.
 */
@SpringBootApplication(scanBasePackages = "com.oneday.auth")
@EntityScan("com.oneday.auth")
@EnableJpaRepositories("com.oneday.auth")
public class AuthLocalApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(AuthLocalApplication.class);
        app.setAdditionalProfiles("authlocal");
        app.run(args);
    }
}
