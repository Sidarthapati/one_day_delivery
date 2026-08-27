package com.oneday.assets.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Asset-registry tunables: registration-photo upload limits and presigned-URL TTLs. Mirrors
 * {@code MeasurementProperties}; the underlying R2 storage config is the shared {@code storage.*} block.
 */
@Component
@ConfigurationProperties(prefix = "assets")
public class AssetProperties {

    /** Max photos accepted per asset registration. */
    private int maxPhotos = 6;

    /** Max bytes for a single photo object (advisory; enforced by the client/presign contract). */
    private long maxPhotoBytes = 15L * 1024 * 1024;

    /** TTL (seconds) of the presigned PUT URLs handed to the console for upload. */
    private long uploadUrlTtlSeconds = 300;

    /** TTL (seconds) of the presigned GET URLs handed to the console for viewing. */
    private long viewUrlTtlSeconds = 600;

    public int getMaxPhotos() { return maxPhotos; }
    public void setMaxPhotos(int maxPhotos) { this.maxPhotos = maxPhotos; }

    public long getMaxPhotoBytes() { return maxPhotoBytes; }
    public void setMaxPhotoBytes(long maxPhotoBytes) { this.maxPhotoBytes = maxPhotoBytes; }

    public long getUploadUrlTtlSeconds() { return uploadUrlTtlSeconds; }
    public void setUploadUrlTtlSeconds(long uploadUrlTtlSeconds) { this.uploadUrlTtlSeconds = uploadUrlTtlSeconds; }

    public long getViewUrlTtlSeconds() { return viewUrlTtlSeconds; }
    public void setViewUrlTtlSeconds(long viewUrlTtlSeconds) { this.viewUrlTtlSeconds = viewUrlTtlSeconds; }
}
