package com.oneday.exceptions.integration;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class JiraClientTest {

    private static JiraProperties props(boolean enabled, boolean configured) {
        JiraProperties p = new JiraProperties();
        p.setEnabled(enabled);
        if (configured) {
            p.setBaseUrl("https://jira.example");
            p.setProjectKey("OPS");
            p.setEmail("bot@x.com");
            p.setApiToken("tok");
        }
        return p;
    }

    @Test
    void disabled_isANoOp_andMakesNoHttpCall() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // No expectations set → any HTTP call would fail verification.
        JiraClient client = new JiraClient(props(false, false), builder);

        assertThat(client.createIssue("Damaged box", "It arrived crushed")).isEmpty();
        server.verify(); // no calls made
    }

    @Test
    void enabledButNotConfigured_isANoOp() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer.bindTo(builder).build();
        JiraClient client = new JiraClient(props(true, false), builder);
        assertThat(client.createIssue("x", "y")).isEmpty();
    }

    @Test
    void configured_postsTheIssue_andReturnsTheKey() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://jira.example/rest/api/3/issue"))
                .andExpect(method(POST))
                .andExpect(header("Authorization", org.hamcrest.Matchers.startsWith("Basic ")))
                .andExpect(jsonPath("$.fields.project.key").value("OPS"))
                .andExpect(jsonPath("$.fields.summary").value("Damaged box"))
                .andRespond(withSuccess("{\"key\":\"OPS-42\"}", APPLICATION_JSON));

        JiraClient client = new JiraClient(props(true, true), builder);
        Optional<String> key = client.createIssue("Damaged box", "It arrived crushed");

        assertThat(key).contains("OPS-42");
        server.verify();
    }

    @Test
    void httpFailure_isSwallowed_returnsEmpty() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://jira.example/rest/api/3/issue"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withServerError());

        JiraClient client = new JiraClient(props(true, true), builder);
        assertThat(client.createIssue("x", "y")).isEmpty();
    }
}
