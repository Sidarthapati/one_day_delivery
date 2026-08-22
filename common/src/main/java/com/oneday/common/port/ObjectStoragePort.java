package com.oneday.common.port;

import java.time.Duration;

/**
 * Blob storage for platform binaries (parcel-dimension evidence photos today; proof-of-delivery
 * photos, server-side PDFs, etc. later). Backed by the S3-compatible API — the default adapter
 * targets Cloudflare R2. Buckets are private; browsers/apps read and write only through the
 * short-lived presigned URLs this port hands out.
 *
 * <p>Callers must treat this as best-effort infrastructure: every method can throw
 * {@code ObjectStorageException}, and {@link #isAvailable()} is false when storage is not
 * configured (e.g. local runs without R2 credentials). Business flows that use it (evidence
 * capture) must degrade gracefully rather than fail the user action.</p>
 */
public interface ObjectStoragePort {

    /** True when storage is configured (endpoint + credentials present) and usable. */
    boolean isAvailable();

    /**
     * A presigned {@code PUT} URL the client uploads a single object to directly (bypassing the JVM).
     * The upload must send the same {@code Content-Type}. Key is chosen server-side by the caller.
     */
    String presignPut(String key, String contentType, Duration ttl);

    /** A presigned {@code GET} URL for reading one object (e.g. showing evidence in the ops console). */
    String presignGet(String key, Duration ttl);

    /** True if the object exists (HEAD). Used to verify a client actually completed its upload. */
    boolean exists(String key);

    /** The object's bytes — used server-side to feed the CV engine. Throws if the object is missing. */
    byte[] getBytes(String key);
}
