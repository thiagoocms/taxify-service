package br.com.cshub.taxify.integration.storage;

import org.springframework.beans.factory.DisposableBean;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.io.IOException;
import java.net.URI;
import java.nio.file.NoSuchFileException;

/**
 * Files in an S3 bucket, as {@code s3://bucket/prefix/companyId/file.xml}. Credentials come
 * from the AWS default chain - the EC2 instance role (recommended) or AWS_ACCESS_KEY_ID /
 * AWS_SECRET_ACCESS_KEY. Objects are written with server-side encryption (SSE-S3).
 */
public class S3XmlStorage implements XmlStorageBackend, DisposableBean {

    static final String SCHEME = "s3://";

    private final S3Client client;
    private final String bucket;
    private final String prefix;

    public S3XmlStorage(FiscalXmlStorageProperties.S3 config) {
        this(buildClient(config), config.getBucket(), config.getPrefix());
    }

    S3XmlStorage(S3Client client, String bucket, String prefix) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException("fiscal-xml.storage.s3.bucket is required when fiscal-xml.storage.type=s3");
        }
        this.client = client;
        this.bucket = bucket.strip();
        this.prefix = prefix == null ? "" : prefix.strip().replaceAll("^/+|/+$", "");
    }

    private static S3Client buildClient(FiscalXmlStorageProperties.S3 config) {
        S3ClientBuilder builder = S3Client.builder().region(Region.of(config.getRegion()));
        if (config.getEndpoint() != null && !config.getEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(config.getEndpoint().strip())).forcePathStyle(true);
        }
        return builder.build();
    }

    @Override
    public String write(String key, byte[] content) throws IOException {
        String objectKey = prefix.isEmpty() ? key : prefix + "/" + key;
        try {
            client.putObject(PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(objectKey)
                            .contentType("application/xml")
                            .serverSideEncryption(ServerSideEncryption.AES256)
                            .build(),
                    RequestBody.fromBytes(content));
        } catch (S3Exception e) {
            throw new IOException("Could not write s3://" + bucket + "/" + objectKey, e);
        }
        return SCHEME + bucket + "/" + objectKey;
    }

    @Override
    public byte[] read(String location) throws IOException {
        Location target = parse(location);
        try {
            return client.getObjectAsBytes(GetObjectRequest.builder().bucket(target.bucket()).key(target.key()).build()).asByteArray();
        } catch (NoSuchKeyException e) {
            throw new NoSuchFileException(location);
        } catch (S3Exception e) {
            throw new IOException("Could not read " + location, e);
        }
    }

    @Override
    public boolean delete(String location) throws IOException {
        Location target = parse(location);
        try {
            client.headObject(HeadObjectRequest.builder().bucket(target.bucket()).key(target.key()).build());
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw new IOException("Could not check " + location, e);
        }
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(target.bucket()).key(target.key()).build());
            return true;
        } catch (S3Exception e) {
            throw new IOException("Could not delete " + location, e);
        }
    }

    @Override
    public boolean handles(String location) {
        return location.startsWith(SCHEME);
    }

    @Override
    public void destroy() {
        client.close();
    }

    record Location(String bucket, String key) {
    }

    /** "s3://bucket/a/b.xml" → (bucket, "a/b.xml"). */
    static Location parse(String location) throws IOException {
        if (location == null || !location.startsWith(SCHEME)) {
            throw new IOException("Not an S3 location: " + location);
        }
        String rest = location.substring(SCHEME.length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash == rest.length() - 1) {
            throw new IOException("Not an S3 location: " + location);
        }
        return new Location(rest.substring(0, slash), rest.substring(slash + 1));
    }
}
