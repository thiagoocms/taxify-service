package br.com.cshub.taxify.integration.storage;

import java.io.IOException;

/**
 * Where XML bytes physically live. A stored file is identified by its <em>location</em>
 * string (kept in FiscalDocument.xmlFilePath): a filesystem path for the disk, an
 * {@code s3://bucket/key} URI for S3 - so each location says which backend reads it.
 */
public interface XmlStorageBackend {

    /** @param key "{companyId}/{fileName}" - the same layout on every backend */
    String write(String key, byte[] content) throws IOException;

    /** @throws java.nio.file.NoSuchFileException when nothing is stored there */
    byte[] read(String location) throws IOException;

    /** @return false when there was nothing to delete */
    boolean delete(String location) throws IOException;

    boolean handles(String location);
}
