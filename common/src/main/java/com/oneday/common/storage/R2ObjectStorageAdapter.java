package com.oneday.common.storage;

import com.oneday.common.port.ObjectStoragePort;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URI;
import java.time.Duration;

/**
 * {@link ObjectStoragePort} backed by the S3-compatible API (Cloudflare R2 by default).
 *
 * <p>Uses static credentials + an endpoint override with path-style addressing (required for a
 * custom S3 endpoint). When {@link ObjectStorageProperties#isConfigured()} is false the adapter
 * stays inert ({@code isAvailable()=false}) so local/CI runs without R2 credentials still boot.</p>
 */
@Component
public class R2ObjectStorageAdapter implements ObjectStoragePort {

    private static final Logger log = LoggerFactory.getLogger(R2ObjectStorageAdapter.class);

    private final ObjectStorageProperties props;
    private final S3Client s3;            // null when not configured
    private final S3Presigner presigner;  // null when not configured

    public R2ObjectStorageAdapter(ObjectStorageProperties props) {
        this.props = props;
        if (!props.isConfigured()) {
            log.warn("Object storage not configured (missing endpoint/bucket/keys) — evidence upload disabled");
            this.s3 = null;
            this.presigner = null;
            return;
        }
        var creds = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(props.getAccessKeyId(), props.getSecretAccessKey()));
        var region = Region.of(props.getRegion());
        var endpoint = URI.create(props.getEndpoint());
        var s3cfg = S3Configuration.builder().pathStyleAccessEnabled(true).build();
        this.s3 = S3Client.builder()
                .endpointOverride(endpoint).region(region).credentialsProvider(creds)
                .serviceConfiguration(s3cfg).build();
        this.presigner = S3Presigner.builder()
                .endpointOverride(endpoint).region(region).credentialsProvider(creds)
                .serviceConfiguration(s3cfg).build();
        log.info("Object storage adapter ready (bucket={})", props.getBucket());
    }

    @Override
    public boolean isAvailable() {
        return s3 != null && presigner != null;
    }

    @Override
    public String presignPut(String key, String contentType, Duration ttl) {
        requireAvailable();
        try {
            PutObjectRequest put = PutObjectRequest.builder()
                    .bucket(props.getBucket()).key(physicalKey(key)).contentType(contentType).build();
            PutObjectPresignRequest presign = PutObjectPresignRequest.builder()
                    .signatureDuration(ttl).putObjectRequest(put).build();
            return presigner.presignPutObject(presign).url().toString();
        } catch (S3Exception e) {
            throw new ObjectStorageException("Failed to presign PUT for key " + key, e);
        }
    }

    @Override
    public String presignGet(String key, Duration ttl) {
        requireAvailable();
        try {
            GetObjectRequest get = GetObjectRequest.builder().bucket(props.getBucket()).key(physicalKey(key)).build();
            GetObjectPresignRequest presign = GetObjectPresignRequest.builder()
                    .signatureDuration(ttl).getObjectRequest(get).build();
            return presigner.presignGetObject(presign).url().toString();
        } catch (S3Exception e) {
            throw new ObjectStorageException("Failed to presign GET for key " + key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        requireAvailable();
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(props.getBucket()).key(physicalKey(key)).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            // R2 returns 404 for a missing key on HEAD without the typed NoSuchKeyException.
            if (e.statusCode() == 404) {
                return false;
            }
            throw new ObjectStorageException("HEAD failed for key " + key, e);
        }
    }

    @Override
    public byte[] getBytes(String key) {
        requireAvailable();
        try {
            ResponseBytes<GetObjectResponse> bytes = s3.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(props.getBucket()).key(physicalKey(key)).build());
            return bytes.asByteArray();
        } catch (S3Exception e) {
            throw new ObjectStorageException("GET failed for key " + key, e);
        }
    }

    private void requireAvailable() {
        if (!isAvailable()) {
            throw new ObjectStorageException("Object storage is not configured");
        }
    }

    /** Prepend the environment key-prefix (if any) so environments sharing a bucket never intermix. */
    private String physicalKey(String logicalKey) {
        String prefix = props.getKeyPrefix();
        if (prefix == null || prefix.isBlank()) {
            return logicalKey;
        }
        return prefix.replaceAll("/+$", "") + "/" + logicalKey;
    }

    @PreDestroy
    void close() {
        if (s3 != null) s3.close();
        if (presigner != null) presigner.close();
    }
}
