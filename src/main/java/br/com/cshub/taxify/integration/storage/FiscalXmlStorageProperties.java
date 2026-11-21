package br.com.cshub.taxify.integration.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Where the fiscal XML files go ({@code fiscal-xml.storage.*}). {@code type=local} keeps
 * them under {@code fiscal-xml.sync.storage-path} (a Docker volume); {@code type=s3} writes
 * new files to the bucket - files already on disk are still read from there until the
 * migration ({@link #isMigrateLocal()}) copies them to S3.
 */
@Component
@ConfigurationProperties(prefix = "fiscal-xml.storage")
public class FiscalXmlStorageProperties {

    public enum Type {
        LOCAL,
        S3
    }

    private Type type = Type.LOCAL;

    /** With type=s3: copy the files still on disk to the bucket in the background on startup. */
    private boolean migrateLocal;

    private final S3 s3 = new S3();

    public static class S3 {
        private String bucket;
        private String region = "us-east-2";
        /** Key prefix inside the bucket - files go to {prefix}/{companyId}/{file}. */
        private String prefix = "fiscal-xml";
        /** Only for S3-compatible services (MinIO, LocalStack); empty = AWS. */
        private String endpoint;

        public String getBucket() {
            return bucket;
        }

        public void setBucket(String bucket) {
            this.bucket = bucket;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public String getPrefix() {
            return prefix;
        }

        public void setPrefix(String prefix) {
            this.prefix = prefix;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public boolean isMigrateLocal() {
        return migrateLocal;
    }

    public void setMigrateLocal(boolean migrateLocal) {
        this.migrateLocal = migrateLocal;
    }

    public S3 getS3() {
        return s3;
    }
}
