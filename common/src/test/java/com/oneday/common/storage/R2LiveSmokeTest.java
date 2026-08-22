package com.oneday.common.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live round-trip against the real Cloudflare R2 bucket, exercising the exact presign→PUT→exists→GET
 * path the app uses. Runs ONLY when the R2 env vars are present (source .env first), so CI without
 * credentials skips it. Writes a tiny object under {@code smoke-test/} and reads it back.
 */
@EnabledIfEnvironmentVariable(named = "R2_ENDPOINT", matches = ".+")
class R2LiveSmokeTest {

    @Test
    void presignUploadAndReadBack() throws Exception {
        ObjectStorageProperties props = new ObjectStorageProperties();
        props.setEndpoint(System.getenv("R2_ENDPOINT"));
        props.setRegion(getenvOr("R2_REGION", "auto"));
        props.setAccessKeyId(System.getenv("R2_ACCESS_KEY_ID"));
        props.setSecretAccessKey(System.getenv("R2_SECRET_ACCESS_KEY"));
        props.setBucket(System.getenv("R2_BUCKET"));

        R2ObjectStorageAdapter adapter = new R2ObjectStorageAdapter(props);
        assertThat(adapter.isAvailable()).isTrue();

        String key = "smoke-test/" + UUID.randomUUID() + ".txt";
        byte[] body = ("hello-r2-" + System.nanoTime()).getBytes(StandardCharsets.UTF_8);

        String putUrl = adapter.presignPut(key, "text/plain", Duration.ofMinutes(5));
        HttpResponse<Void> put = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(putUrl))
                        .header("Content-Type", "text/plain")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(put.statusCode()).isBetween(200, 204);

        assertThat(adapter.exists(key)).isTrue();
        assertThat(adapter.getBytes(key)).isEqualTo(body);
        assertThat(adapter.exists("smoke-test/does-not-exist-" + UUID.randomUUID())).isFalse();
    }

    private static String getenvOr(String k, String d) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? d : v;
    }
}
