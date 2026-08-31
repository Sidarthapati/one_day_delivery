package com.oneday.exceptions.integration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Jira integration config (roadmap #14, chat→Jira). Disabled by default — the platform ships without
 * Jira and flips it on when an ops team provides real credentials. NEVER commit secrets: set
 * {@code apiToken} (and the rest) via environment variables only.
 *
 * <pre>{@code
 * integrations:
 *   jira:
 *     enabled: true
 *     base-url: https://your-org.atlassian.net
 *     project-key: OPS
 *     email: bot@your-org.com
 *     api-token: ${JIRA_API_TOKEN}
 *     issue-type: Task
 * }</pre>
 */
@Component
@ConfigurationProperties(prefix = "integrations.jira")
public class JiraProperties {

    /** Master switch — when false, JiraClient is a logged no-op. Default false. */
    private boolean enabled = false;
    private String baseUrl;
    private String projectKey;
    private String email;
    private String apiToken;
    private String issueType = "Task";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getProjectKey() { return projectKey; }
    public void setProjectKey(String projectKey) { this.projectKey = projectKey; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getApiToken() { return apiToken; }
    public void setApiToken(String apiToken) { this.apiToken = apiToken; }
    public String getIssueType() { return issueType; }
    public void setIssueType(String issueType) { this.issueType = issueType; }

    /** True only when enabled AND all required connection fields are present. */
    public boolean isConfigured() {
        return enabled && notBlank(baseUrl) && notBlank(projectKey) && notBlank(email) && notBlank(apiToken);
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
}
