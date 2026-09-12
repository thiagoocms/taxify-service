package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlPurgeProperties;
import br.com.cshub.taxify.repository.FiscalXmlDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FiscalXmlPurgeSchedulerTest {

    @Mock
    private FiscalXmlDocumentRepository documentRepository;

    private FiscalXmlPurgeProperties properties;
    private FiscalXmlPurgeScheduler scheduler;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        properties = new FiscalXmlPurgeProperties();
        properties.setRetentionMonths(3);
        scheduler = new FiscalXmlPurgeScheduler(documentRepository, properties);
    }

    @Test
    void doesNothingWhenDisabled() {
        properties.setEnabled(false);

        scheduler.purgeOldFiles();

        verifyNoInteractions(documentRepository);
    }

    @Test
    void deletesTheOriginalAndRectifiedFilesOfAnOldDocument() throws IOException {
        Path original = tempDir.resolve("original.xml");
        Path rectified = tempDir.resolve("original-retificado.xml");
        Files.writeString(original, "<a/>", StandardCharsets.UTF_8);
        Files.writeString(rectified, "<a/>", StandardCharsets.UTF_8);

        FiscalXmlDocument document = new FiscalXmlDocument();
        document.setId(UUID.randomUUID());
        document.setXmlFilePath(original.toString());
        document.setRectifiedFilePath(rectified.toString());
        when(documentRepository.findByIssueDateBeforeAndDeletedIsFalse(any())).thenReturn(List.of(document));

        scheduler.purgeOldFiles();

        assertThat(Files.exists(original)).isFalse();
        assertThat(Files.exists(rectified)).isFalse();
    }

    @Test
    void usesTheConfiguredRetentionMonthsAsTheCutoff() {
        properties.setRetentionMonths(6);
        when(documentRepository.findByIssueDateBeforeAndDeletedIsFalse(any())).thenReturn(List.of());

        LocalDateTime before = LocalDateTime.now().minusMonths(6);
        scheduler.purgeOldFiles();
        LocalDateTime after = LocalDateTime.now().minusMonths(6);

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(documentRepository).findByIssueDateBeforeAndDeletedIsFalse(captor.capture());
        assertThat(captor.getValue()).isBetween(before, after);
    }

    @Test
    void skipsADocumentWithNoFilesLeftInsteadOfFailing() {
        FiscalXmlDocument alreadyGone = new FiscalXmlDocument();
        alreadyGone.setId(UUID.randomUUID());
        alreadyGone.setXmlFilePath(tempDir.resolve("does-not-exist.xml").toString());
        when(documentRepository.findByIssueDateBeforeAndDeletedIsFalse(any())).thenReturn(List.of(alreadyGone));

        // Doesn't throw, doesn't need any file to actually exist - Files.deleteIfExists
        // is a safe no-op, so re-running over an already-purged document is harmless.
        scheduler.purgeOldFiles();
    }

    @Test
    void oneFailingDocumentDoesNotStopTheRest() throws IOException {
        FiscalXmlDocument invalidPath = new FiscalXmlDocument();
        invalidPath.setId(UUID.randomUUID());
        // Illegal on Windows (":" mid-name) - Path.of throws InvalidPathException,
        // exercising the per-document catch without affecting the next document.
        invalidPath.setXmlFilePath("bad:path?.xml");

        Path ok = tempDir.resolve("ok.xml");
        Files.writeString(ok, "<a/>", StandardCharsets.UTF_8);
        FiscalXmlDocument okDocument = new FiscalXmlDocument();
        okDocument.setId(UUID.randomUUID());
        okDocument.setXmlFilePath(ok.toString());

        when(documentRepository.findByIssueDateBeforeAndDeletedIsFalse(any())).thenReturn(List.of(invalidPath, okDocument));

        scheduler.purgeOldFiles();

        assertThat(Files.exists(ok)).isFalse();
    }
}
