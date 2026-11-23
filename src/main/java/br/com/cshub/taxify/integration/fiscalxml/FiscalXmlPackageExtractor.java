package br.com.cshub.taxify.integration.fiscalxml;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Splits a raw fiscal-XML payload into individual XML byte payloads - a body can be a
 * single XML, or a zip batching several (verified against SIEG's baixar-xmls response;
 * also used for manual .zip uploads, see FiscalXmlIntegrationService#importManual). Not
 * provider-specific: this is plain zip/XML packaging, not anything SIEG invented.
 *
 * <p>{@link #forEachXml} streams: one XML in memory at a time, however big the zip is.
 */
@Component
public class FiscalXmlPackageExtractor {

    private static final Logger log = LoggerFactory.getLogger(FiscalXmlPackageExtractor.class);
    private static final byte[] ZIP_SIGNATURE = {'P', 'K'};
    /** No fiscal XML gets near this - a bigger entry is skipped (zip bomb guard). */
    static final int MAX_XML_BYTES = 20 * 1024 * 1024;

    /** All the XMLs at once - for small payloads (a SIEG page). Big uploads go through {@link #forEachXml}. */
    public List<byte[]> extract(byte[] content) {
        List<byte[]> xmlContents = new ArrayList<>();
        if (content == null || content.length == 0) {
            return xmlContents;
        }
        try {
            forEachXml(new ByteArrayInputStream(content), xmlContents::add);
        } catch (IOException e) {
            log.warn("Failed to read a fiscal XML package", e);
        }
        return xmlContents;
    }

    /**
     * Hands each XML of the payload to {@code consumer}, one at a time: the entries of a zip, or the
     * payload itself when it isn't one.
     *
     * @return how many were handed over
     */
    public int forEachXml(InputStream input, Consumer<byte[]> consumer) throws IOException {
        BufferedInputStream in = new BufferedInputStream(input);
        in.mark(2);
        byte[] head = in.readNBytes(2);
        in.reset();
        if (head.length == 0) {
            return 0;
        }
        if (head.length < 2 || head[0] != ZIP_SIGNATURE[0] || head[1] != ZIP_SIGNATURE[1]) {
            byte[] content = readLimited(in);
            if (content == null) {
                log.warn("Fiscal XML upload over {} bytes skipped", MAX_XML_BYTES);
                return 0;
            }
            consumer.accept(content);
            return 1;
        }
        int count = 0;
        try (ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory() || !entry.getName().toLowerCase().endsWith(".xml")) {
                    continue;
                }
                byte[] xml = readLimited(zis);
                if (xml == null) {
                    log.warn("Zip entry {} over {} bytes skipped", entry.getName(), MAX_XML_BYTES);
                    continue;
                }
                consumer.accept(xml);
                count++;
            }
        }
        return count;
    }

    /** The rest of the stream, or null when it passes {@link #MAX_XML_BYTES}. */
    private static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            if (buffer.size() + read > MAX_XML_BYTES) {
                // drain what's left of this entry without keeping it
                while (in.read(chunk) != -1) {
                    // skip
                }
                return null;
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }
}
