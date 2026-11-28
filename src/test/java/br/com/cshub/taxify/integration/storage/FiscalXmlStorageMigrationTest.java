package br.com.cshub.taxify.integration.storage;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.fiscaldocument.GoodsInvoice;
import br.com.cshub.taxify.support.TestNotes;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncProperties;
import br.com.cshub.taxify.integration.sieg.FiscalXmlFileStorage;
import br.com.cshub.taxify.repository.FiscalDocumentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FiscalXmlStorageMigrationTest {

    @TempDir
    Path disk;

    /** S3 stand-in keeping the objects in memory. */
    static class MemoryS3 extends S3XmlStorage {
        final Map<String, byte[]> objects = new HashMap<>();

        MemoryS3() {
            super(mock(S3Client.class), "bucket", "fiscal-xml");
        }

        @Override
        public String write(String key, byte[] content) {
            String location = "s3://bucket/fiscal-xml/" + key;
            objects.put(location, content);
            return location;
        }

        @Override
        public byte[] read(String location) throws IOException {
            byte[] content = objects.get(location);
            if (content == null) {
                throw new NoSuchFileException(location);
            }
            return content;
        }
    }

    @SuppressWarnings("unchecked")
    private FiscalXmlFileStorage s3Storage(MemoryS3 s3) {
        FiscalXmlSyncProperties sync = new FiscalXmlSyncProperties();
        sync.setStoragePath(disk.toString());
        FiscalXmlStorageProperties properties = new FiscalXmlStorageProperties();
        properties.setType(FiscalXmlStorageProperties.Type.S3);
        ObjectProvider<S3XmlStorage> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(s3);
        return new FiscalXmlFileStorage(new LocalXmlStorage(sync), provider, properties, mock(MessageService.class));
    }

    @Test
    void newFilesGoToS3AndOldOnesAreStillReadFromDisk() throws IOException {
        FiscalXmlFileStorage storage = s3Storage(new MemoryS3());
        Path old = Files.createDirectories(disk.resolve("company-1")).resolve("old.xml");
        Files.writeString(old, "<old/>");

        String created = storage.store(UUID.fromString("00000000-0000-0000-0000-000000000001"), "NEW", "<new/>".getBytes());

        assertThat(created).isEqualTo("s3://bucket/fiscal-xml/00000000-0000-0000-0000-000000000001/NEW.xml");
        assertThat(new String(storage.read(created))).isEqualTo("<new/>");
        assertThat(new String(storage.read(old.toString()))).isEqualTo("<old/>");
    }

    @Test
    void copiesDiskFilesToS3KeepingTheKeyAndSkipsPurgedOnes() throws IOException {
        MemoryS3 s3 = new MemoryS3();
        FiscalXmlFileStorage storage = s3Storage(s3);
        Path original = Files.createDirectories(disk.resolve("company-1")).resolve("KEY.xml");
        Files.writeString(original, "<nfe/>");

        GoodsInvoice onDisk = TestNotes.note(TestNotes.company(TestNotes.COMPANY_CNPJ)).xml(original.toString()).build();
        // rectified copy already purged: not on disk any more
        onDisk.setRectifiedFilePath(disk.resolve("company-1").resolve("KEY-retificado-1.xml").toString());
        FiscalDocumentRepository repository = mock(FiscalDocumentRepository.class);
        when(repository.findWithFileOutsideS3(eq(""), any())).thenReturn(List.of(onDisk));
        when(repository.findWithFileOutsideS3(eq(onDisk.getId().toString()), any())).thenReturn(List.of());
        FiscalXmlStorageProperties properties = new FiscalXmlStorageProperties();
        properties.setType(FiscalXmlStorageProperties.Type.S3);

        new FiscalXmlStorageMigration(properties, repository, storage).migrateAll();

        assertThat(onDisk.getXmlFilePath()).isEqualTo("s3://bucket/fiscal-xml/company-1/KEY.xml");
        assertThat(new String(s3.objects.get(onDisk.getXmlFilePath()))).isEqualTo("<nfe/>");
        assertThat(onDisk.getRectifiedFilePath()).doesNotStartWith("s3://");
        assertThat(Files.exists(original)).isTrue();
        verify(repository).save(onDisk);
    }
}
