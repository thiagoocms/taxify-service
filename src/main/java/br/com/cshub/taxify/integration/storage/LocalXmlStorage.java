package br.com.cshub.taxify.integration.storage;

import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Files on disk under {@code fiscal-xml.sync.storage-path} (the Docker volume). */
@Component
public class LocalXmlStorage implements XmlStorageBackend {

    private final FiscalXmlSyncProperties properties;

    public LocalXmlStorage(FiscalXmlSyncProperties properties) {
        this.properties = properties;
    }

    @Override
    public String write(String key, byte[] content) throws IOException {
        Path file = Path.of(properties.getStoragePath()).resolve(key);
        Files.createDirectories(file.getParent());
        Files.write(file, content);
        return file.toString();
    }

    @Override
    public byte[] read(String location) throws IOException {
        return Files.readAllBytes(Path.of(location));
    }

    @Override
    public boolean delete(String location) throws IOException {
        return Files.deleteIfExists(Path.of(location));
    }

    /** Everything that isn't a URI of another backend. */
    @Override
    public boolean handles(String location) {
        return !location.startsWith(S3XmlStorage.SCHEME);
    }
}
