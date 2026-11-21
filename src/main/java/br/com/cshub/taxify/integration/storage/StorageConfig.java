package br.com.cshub.taxify.integration.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The S3 backend exists only when it's the chosen storage - no AWS client otherwise. */
@Configuration
public class StorageConfig {

    @Bean
    @ConditionalOnProperty(prefix = "fiscal-xml.storage", name = "type", havingValue = "s3")
    public S3XmlStorage s3XmlStorage(FiscalXmlStorageProperties properties) {
        return new S3XmlStorage(properties.getS3());
    }
}
