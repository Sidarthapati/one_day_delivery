package com.oneday.exceptions.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST implementation of {@link JiraPort} over the Jira Cloud v3 API. Off by default; when enabled +
 * configured it POSTs {@code /rest/api/3/issue} with basic auth (email:apiToken). Best-effort — any
 * failure is logged and swallowed so raising a support ticket never depends on Jira being reachable.
 */
@Component
class JiraClient implements JiraPort {

    private static final Logger log = LoggerFactory.getLogger(JiraClient.class);

    private final JiraProperties props;
    private final RestClient.Builder restClientBuilder;

    JiraClient(JiraProperties props, RestClient.Builder restClientBuilder) {
        this.props = props;
        this.restClientBuilder = restClientBuilder;
    }

    @Override
    public Optional<String> createIssue(String summary, String description) {
        if (!props.isEnabled()) {
            log.debug("[jira] disabled — skipping issue creation for '{}'", summary);
            return Optional.empty();
        }
        if (!props.isConfigured()) {
            log.warn("[jira] enabled but not fully configured (base-url/project-key/email/api-token) — skipping");
            return Optional.empty();
        }
        try {
            String basic = Base64.getEncoder().encodeToString(
                    (props.getEmail() + ":" + props.getApiToken()).getBytes(StandardCharsets.UTF_8));
            Map<?, ?> response = restClientBuilder.baseUrl(props.getBaseUrl()).build()
                    .post()
                    .uri("/rest/api/3/issue")
                    .header(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(issuePayload(summary, description))
                    .retrieve()
                    .body(Map.class);
            Object key = response == null ? null : response.get("key");
            if (key == null) {
                log.warn("[jira] issue created but no key in response");
                return Optional.empty();
            }
            log.info("[jira] created issue {} for '{}'", key, summary);
            return Optional.of(key.toString());
        } catch (Exception e) {
            log.warn("[jira] issue creation failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Jira Cloud v3 create-issue body (description in the minimal Atlassian Document Format). */
    private Map<String, Object> issuePayload(String summary, String description) {
        Map<String, Object> textNode = Map.of("type", "text", "text", description == null ? "" : description);
        Map<String, Object> paragraph = Map.of("type", "paragraph", "content", List.of(textNode));
        Map<String, Object> doc = Map.of("type", "doc", "version", 1, "content", List.of(paragraph));
        Map<String, Object> fields = Map.of(
                "project", Map.of("key", props.getProjectKey()),
                "issuetype", Map.of("name", props.getIssueType()),
                "summary", summary,
                "description", doc);
        return Map.of("fields", fields);
    }
}
