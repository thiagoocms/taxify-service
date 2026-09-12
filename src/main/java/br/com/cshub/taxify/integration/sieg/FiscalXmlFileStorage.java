package br.com.cshub.taxify.integration.sieg;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
import br.com.cshub.taxify.exception.SiegIntegrationException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Writes fiscal XML bytes to disk under {@link SiegProperties#getXmlStoragePath()},
 * organized by company. {@link FiscalXmlDocument} keeps only the resulting path plus
 * the parsed field values - not the XML content itself.
 */
@Component
public class FiscalXmlFileStorage {

    private final SiegProperties properties;
    private final MessageService messageService;

    public FiscalXmlFileStorage(SiegProperties properties, MessageService messageService) {
        this.properties = properties;
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

    private String fileName(String accessKey, String suffix) {
        return (accessKey != null && !accessKey.isBlank() ? accessKey : UUID.randomUUID().toString()) + suffix;
    }

    private String write(UUID companyId, String fileName, byte[] xmlBytes) {
        Path directory = Path.of(properties.getXmlStoragePath(), companyId.toString());
        Path file = directory.resolve(fileName);
        try {
            Files.createDirectories(directory);
            Files.write(file, xmlBytes);
            return file.toString();
        } catch (IOException e) {
            throw new SiegIntegrationException(messageService.getMessage("error.sieg.xml.storage.failed"), e);
        }
    }
}
