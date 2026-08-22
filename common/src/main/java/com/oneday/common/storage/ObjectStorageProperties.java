package com.oneday.common.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Object-storage (Cloudflare R2 / S3-compatible) configuration.
 *
 * <p>Credentials are supplied via environment variables ONLY — never committed. Bind e.g.
 * {@code R2_ENDPOINT}, {@code R2_ACCESS_KEY_ID}, {@code R2_SECRET_ACCESS_KEY}, {@code R2_BUCKET}
 * through {@code application.yml} placeholders (see app config). When {@code endpoint}/{@code bucket}
 * or the keys are blank the adapter reports {@code isAvailable()=false} and storage-backed features
 * degrade gracefully.</p>
 */
@Component
@ConfigurationProperties(prefix = "storage")
public class ObjectStorageProperties {

    /** S3 API endpoint, e.g. https://<accountid>.r2.cloudflarestorage.com (no bucket suffix). */
    private String endpoint = "";

    /** R2 is region-agnostic; the SDK still needs a value — use "auto". */
    private String region = "auto";

    private String accessKeyId = "";

    private String secretAccessKey = "";

    /** Bucket name, e.g. oneday-parcel-evidence. May be env-suffixed for hard per-env isolation. */
    private String bucket = "";

    /**
     * Environment key-prefix (e.g. "dev", "staging", "prod"). Applied transparently by the adapter to
     * every object key, so environments sharing one bucket never intermix. Blank = no prefix.
     * App/DB keep using logical keys ({@code pickup-measurements/...}); the adapter stores them under
     * {@code <prefix>/pickup-measurements/...}.
     */
    private String keyPrefix = "";

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getAccessKeyId() { return accessKeyId; }
    public void setAccessKeyId(String accessKeyId) { this.accessKeyId = accessKeyId; }

    public String getSecretAccessKey() { return secretAccessKey; }
    public void setSecretAccessKey(String secretAccessKey) { this.secretAccessKey = secretAccessKey; }

    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }

    public String getKeyPrefix() { return keyPrefix; }
    public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }

    /** True when endpoint, bucket, and both keys are all present. */
    public boolean isConfigured() {
        return notBlank(endpoint) && notBlank(bucket) && notBlank(accessKeyId) && notBlank(secretAccessKey);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
