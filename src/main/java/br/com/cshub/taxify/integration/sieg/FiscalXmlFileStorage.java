package br.com.cshub.taxify.integration.sieg;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocument;
import br.com.cshub.taxify.exception.SiegIntegrationException;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncProperties;
import br.com.cshub.taxify.integration.storage.FiscalXmlStorageProperties;
import br.com.cshub.taxify.integration.storage.LocalXmlStorage;
import br.com.cshub.taxify.integration.storage.S3XmlStorage;
import br.com.cshub.taxify.integration.storage.XmlStorageBackend;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The one place fiscal XML files are written, read and deleted. New files go to the
 * configured storage ({@code fiscal-xml.storage.type}: local disk or S3), organized as
 * {companyId}/{file}; each file is read back from wherever its location says it is, so
 * notes stored on disk before the move to S3 keep opening. {@link FiscalDocument}
 * keeps only that location plus the parsed field values - not the XML itself.
 */
@Component
public class FiscalXmlFileStorage {

    private final XmlStorageBackend writer;
    private final List<XmlStorageBackend> readers;
    private final MessageService messageService;

    @Autowired
    public FiscalXmlFileStorage(LocalXmlStorage local, ObjectProvider<S3XmlStorage> s3, FiscalXmlStorageProperties properties,
                                MessageService messageService) {
        this.messageService = messageService;
        List<XmlStorageBackend> backends = new ArrayList<>();
        S3XmlStorage s3Storage = s3.getIfAvailable();
        if (s3Storage != null) {
            backends.add(s3Storage);
        }
        backends.add(local);
        this.readers = List.copyOf(backends);
        if (properties.getType() == FiscalXmlStorageProperties.Type.S3) {
            if (s3Storage == null) {
                throw new IllegalStateException("fiscal-xml.storage.type=s3 but the S3 storage is not configured");
            }
            this.writer = s3Storage;
        } else {
            this.writer = local;
        }
    }

    /** Local disk only - for tests and tools. */
    public FiscalXmlFileStorage(FiscalXmlSyncProperties properties, MessageService messageService) {
        LocalXmlStorage local = new LocalXmlStorage(properties);
        this.writer = local;
        this.readers = List.of(local);
        this.messageService = messageService;
    }

    public String store(UUID companyId, String accessKey, byte[] xmlBytes) {
        return write(companyId, fileName(accessKey, ".xml"), xmlBytes);
    }

    /**
     * Writes a rectified copy of a note (see {@link br.com.cshub.taxify.service.FiscalXmlRectificationService})
     * next to the original, never overwriting it - each rectification run gets its own
     * timestamped file, keeping every corrected version around.
     */
    public String storeRectified(UUID companyId, String accessKey, byte[] xmlBytes) {
        return write(companyId, fileName(accessKey, "-retificado-" + System.currentTimeMillis() + ".xml"), xmlBytes);
    }

    /** @throws java.nio.file.NoSuchFileException when the file is gone (purged) */
    /**
     * An A1 certificate - encrypted by the caller (SecretCipher) when a key is set - in the same
     * backend as the XMLs (the bucket in production), under "certificados/".
     *
     * @param suffix ".pfx.enc" when encrypted, ".pfx" otherwise - tells how to read it back
     */
    public String storeCertificate(UUID companyId, byte[] content, String suffix) throws IOException {
        return writer.write("certificados/" + companyId + "-" + System.currentTimeMillis() + suffix, content);
    }

    /** The Fronteira extract PDF of a company and period ("2026-11") - one file per import. */
    public String storeExtractPdf(UUID companyId, String period, byte[] pdf) throws IOException {
        return writer.write("fronteira/" + companyId + "-" + period + "-" + System.currentTimeMillis() + ".pdf", pdf);
    }

    /** A state certidão PDF (Certidão SEFAZ-PE) of a company - one file per certidão, beside the Fronteira extracts. */
    public String storeTaxClearancePdf(UUID companyId, String uf, byte[] pdf) throws IOException {
        return writer.write("certidoes/" + companyId + "-" + uf + "-" + System.currentTimeMillis() + ".pdf", pdf);
    }

    public byte[] read(String location) throws IOException {
        return backendOf(location).read(location);
    }

    /** @return false when there was nothing to delete */
    public boolean delete(String location) throws IOException {
        if (location == null || location.isBlank()) {
            return false;
        }
        return backendOf(location).delete(location);
    }

    /** Whether a location is already where new files are written (e.g. on S3 after the move). */
    public boolean isOnCurrentStorage(String location) {
        return location != null && writer.handles(location);
    }

    /**
     * Copies a file to the current storage keeping its {companyId}/{file} key - the move from
     * disk to S3. Returns the new location (the original file is left in place).
     */
    public String copyToCurrentStorage(String location) throws IOException {
        byte[] content = read(location);
        Path path = Path.of(location);
        Path parent = path.getParent();
        String key = (parent == null || parent.getFileName() == null ? "" : parent.getFileName() + "/") + path.getFileName();
        return writer.write(key, content);
    }

    private String fileName(String accessKey, String suffix) {
        return (accessKey != null && !accessKey.isBlank() ? accessKey : UUID.randomUUID().toString()) + suffix;
    }

    private String write(UUID companyId, String fileName, byte[] xmlBytes) {
        try {
            return writer.write(companyId + "/" + fileName, xmlBytes);
        } catch (IOException e) {
            throw new SiegIntegrationException("error.sieg.xml.storage.failed", messageService.getMessage("error.sieg.xml.storage.failed"), e);
        }
    }

    private XmlStorageBackend backendOf(String location) throws IOException {
        if (location == null || location.isBlank()) {
            throw new java.nio.file.NoSuchFileException(String.valueOf(location));
        }
        for (XmlStorageBackend backend : readers) {
            if (backend.handles(location)) {
                return backend;
            }
        }
        throw new IOException("No storage configured for " + location + " (is fiscal-xml.storage.type=s3 set?)");
    }
}
