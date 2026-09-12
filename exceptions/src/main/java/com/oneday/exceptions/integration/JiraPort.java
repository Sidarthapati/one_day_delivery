package com.oneday.exceptions.integration;

import java.util.Optional;

/**
 * Creates a Jira issue for a support ticket (roadmap #14). Fire-and-forget and best-effort: a failure
 * (or a disabled integration) returns empty and never breaks the ticket flow.
 */
public interface JiraPort {

    /**
     * @param summary     the issue summary (one line)
     * @param description plain-text body
     * @return the created issue key (e.g. "OPS-123"), or empty if the integration is off / not
     *         configured / the call failed
     */
    Optional<String> createIssue(String summary, String description);
}
