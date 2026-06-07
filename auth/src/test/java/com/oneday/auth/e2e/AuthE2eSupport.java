package com.oneday.auth.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oneday.auth.dto.request.LoginRequest;
import com.oneday.auth.dto.request.RegisterUserRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared base for the M1 auth full-stack end-to-end tests. Each test drives a real HTTP request
 * through the real Spring Security chain (the production {@code SecurityConfig}: JWT filter +
 * method security), real {@code JwtService} (HS256 sign/verify), real {@code BCryptPasswordEncoder},
 * and a <b>real Postgres</b>. Auth has no external ports, so nothing is mocked — this is the whole
 * module exercised as it runs in production. Kafka auto-config is excluded (auth publishes nothing).
 * Tests are {@code @Transactional}, so users/roles/onboarding rows created in a test roll back,
 * keeping the shared dev DB clean and each scenario isolated. The seeded admin
 * ({@code admin@oneday.in}, from {@code DataInitializer}) is the bootstrap identity.
 */
@SpringBootTest(properties =
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration")
@AutoConfigureMockMvc
@Transactional
abstract class AuthE2eSupport {

    protected static final String ADMIN_EMAIL = "admin@oneday.in";
    protected static final String ADMIN_PASSWORD = "Admin1234!";
    /** Password used for every test-created user. */
    protected static final String PW = "Password123!";

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;

    // ── HTTP helpers ────────────────────────────────────────────────────────

    protected MockHttpServletRequestBuilder asJson(MockHttpServletRequestBuilder b, Object body) throws Exception {
        return b.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
    }

    /** Logs in and returns the signed JWT. */
    protected String login(String email, String password) throws Exception {
        String body = mvc.perform(asJson(post("/auth/login"), new LoginRequest(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asText();
    }

    protected String adminToken() throws Exception {
        return login(ADMIN_EMAIL, ADMIN_PASSWORD);
    }

    protected static String bearer(String token) {
        return "Bearer " + token;
    }

    protected static String uniqueEmail() {
        return "u" + UUID.randomUUID().toString().substring(0, 8) + "@test.in";
    }

    // ── User/role helpers ─────────────────────────────────────────────────────

    /** City-scoped roles (per the seeded role catalogue) require a cityId at creation. */
    private static final java.util.Set<String> CITY_SCOPED = java.util.Set.of(
            "STATION_MANAGER", "DELIVERY_ASSOCIATE", "CALL_CENTER_AGENT", "HUB_OPERATOR",
            "SUPERVISOR", "CRON_DRIVER", "VAN_DRIVER");

    /** "DEL" for city-scoped roles (required), null otherwise. */
    protected static String cityFor(String role) {
        return CITY_SCOPED.contains(role) ? "DEL" : null;
    }

    /** Admin-creates a user with the given role (password {@link #PW}) and returns its email. */
    protected String createUser(String adminToken, String email, String role) throws Exception {
        mvc.perform(asJson(post("/users").header("Authorization", bearer(adminToken)),
                        new RegisterUserRequest("Test " + role, email, PW, role, cityFor(role))))
                .andExpect(status().isOk());
        return email;
    }

    /** Creates a fresh user with {@code role} and returns a JWT logged in as that user. */
    protected String tokenForRole(String role) throws Exception {
        String email = uniqueEmail();
        createUser(adminToken(), email, role);
        return login(email, PW);
    }

    /** Looks up a seeded role's id by name (needed for role-change requests). */
    protected UUID roleId(String adminToken, String roleName) throws Exception {
        String body = mvc.perform(get("/roles").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (JsonNode role : json.readTree(body)) {
            if (roleName.equals(role.get("name").asText())) {
                return UUID.fromString(role.get("id").asText());
            }
        }
        throw new IllegalStateException("Role not found: " + roleName);
    }
}
