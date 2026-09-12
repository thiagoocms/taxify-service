package br.com.cshub.taxify.integration.fiscalxml;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Splits a raw fiscal-XML payload into individual XML byte payloads - a body can be a
 * single XML, or a zip batching several (verified against SIEG's baixar-xmls response;
 * also used for manual .zip uploads, see SiegXmlIntegrationService#importManual). Not
 * provider-specific: this is plain zip/XML packaging, not anything SIEG invented.
 */
@Component
public class FiscalXmlPackageExtractor {

    private static final Logger log = LoggerFactory.getLogger(FiscalXmlPackageExtractor.class);
    private static final byte[] ZIP_SIGNATURE = {'P', 'K'};

    public List<byte[]> extract(byte[] content) {
        if (content == null || content.length == 0) {
            return new ArrayList<>();
        }
        if (isZip(content)) {
            return extractZipEntries(content);
        }
        List<byte[]> xmlContents = new ArrayList<>();
        xmlContents.add(content);
        return xmlContents;
    }

    private boolean isZip(byte[] content) {
        return content.length >= 2 && content[0] == ZIP_SIGNATURE[0] && content[1] == ZIP_SIGNATURE[1];
    }

    private List<byte[]> extractZipEntries(byte[] zipBytes) {
        List<byte[]> entries = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory() || !entry.getName().toLowerCase().endsWith(".xml")) {
                    continue;
                }
                entries.add(readAllBytes(zis));
            }
        } catch (IOException e) {
            log.warn("Failed to read a fiscal XML zip package", e);
        }
        return entries;
    }

    private byte[] readAllBytes(ZipInputStream zis) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = zis.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }
}
