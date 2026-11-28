package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocument;
import br.com.cshub.taxify.domain.fiscaldocument.GoodsInvoice;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlPurgeProperties;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncProperties;
import br.com.cshub.taxify.integration.sieg.FiscalXmlFileStorage;
import br.com.cshub.taxify.repository.FiscalDocumentRepository;
import br.com.cshub.taxify.service.routine.RoutineRunReservation;
import br.com.cshub.taxify.support.TestNotes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** FD-RN-16: the purge in pages of 500 by id, marking xml_purged_at; one run per day ({@code routine_runs}). */
class FiscalXmlPurgeSchedulerTest {

    private static final ZoneId RECIFE = ZoneId.of("America/Recife");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 12, 1, 4, 0);

    private final FiscalDocumentRepository documentRepository = mock(FiscalDocumentRepository.class);
    private final RoutineRunReservation reservation = mock(RoutineRunReservation.class);
    private final Clock clock = Clock.fixed(ZonedDateTime.of(NOW, RECIFE).toInstant(), RECIFE);

    private FiscalXmlPurgeProperties properties;
    private FiscalXmlPurgeScheduler scheduler;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        properties = new FiscalXmlPurgeProperties();
        properties.setRetentionMonths(3);
        FiscalXmlSyncProperties syncProperties = new FiscalXmlSyncProperties();
        syncProperties.setStoragePath(tempDir.toString());
        scheduler = new FiscalXmlPurgeScheduler(documentRepository, properties,
                new FiscalXmlFileStorage(syncProperties, mock(MessageService.class)), reservation,
                mock(PlatformTransactionManager.class), clock);
        when(reservation.reserve(anyString(), anyInt())).thenReturn(Optional.of(
                new RoutineRunReservation.Reservation("run", FiscalXmlPurgeScheduler.ROUTINE, LocalDate.of(2026, 12, 1), false)));
        when(documentRepository.findPurgeCandidates(any(), anyString(), any())).thenReturn(List.of());
    }

    private GoodsInvoice document(String xmlPath) {
        return TestNotes.note(TestNotes.company(TestNotes.COMPANY_CNPJ)).xml(xmlPath).build();
    }

    @Test
    void doesNothingWhenDisabled() {
        properties.setEnabled(false);

        scheduler.purgeOldFiles();

        verifyNoInteractions(documentRepository, reservation);
    }

    @Test
    void anotherInstanceWithTodaysRunSkipsIt() {
        when(reservation.reserve(anyString(), anyInt())).thenReturn(Optional.empty());

        scheduler.purgeOldFiles();

        verifyNoInteractions(documentRepository);
    }

    @Test
    void deletesTheOriginalAndRectifiedFilesAndMarksTheDocument() throws IOException {
        Path original = tempDir.resolve("original.xml");
        Path rectified = tempDir.resolve("original-retificado.xml");
        Files.writeString(original, "<a/>", StandardCharsets.UTF_8);
        Files.writeString(rectified, "<a/>", StandardCharsets.UTF_8);
        GoodsInvoice document = document(original.toString());
        document.setRectifiedFilePath(rectified.toString());
        when(documentRepository.findPurgeCandidates(any(), eq(""), any())).thenReturn(List.of(document));

        scheduler.purgeOldFiles();

        assertThat(Files.exists(original)).isFalse();
        assertThat(Files.exists(rectified)).isFalse();
        assertThat(document.getXmlPurgedAt()).isEqualTo(NOW);
        verify(documentRepository).save(document);
        verify(reservation).finish(any(), eq(1), eq(0));
    }

    @Test
    void usesTheConfiguredRetentionMonthsAsTheCutoff() {
        properties.setRetentionMonths(6);

        scheduler.purgeOldFiles();

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(documentRepository).findPurgeCandidates(captor.capture(), eq(""), any());
        assertThat(captor.getValue()).isEqualTo(NOW.minusMonths(6));
    }

    @Test
    void aDocumentWithNoFileLeftIsMarkedInsteadOfFailing() {
        GoodsInvoice alreadyGone = document(tempDir.resolve("does-not-exist.xml").toString());
        when(documentRepository.findPurgeCandidates(any(), eq(""), any())).thenReturn(List.of(alreadyGone));

        scheduler.purgeOldFiles();

        assertThat(alreadyGone.isXmlPurged()).isTrue();
    }

    /** CA-FD-22: 1.200 candidates = 3 pages by id; a file that fails leaves only that document unmarked. */
    @Test
    void readsThePagesByIdAndOneFailingFileLeavesOnlyItsDocument() throws IOException {
        List<FiscalDocument> all = new ArrayList<>();
        for (int i = 0; i < 1200; i++) {
            Path file = tempDir.resolve("n" + i + ".xml");
            Files.writeString(file, "<a/>", StandardCharsets.UTF_8);
            GoodsInvoice document = document(file.toString());
            document.setId(UUID.fromString(String.format("00000000-0000-0000-0000-%012d", i)));
            all.add(document);
        }
        // illegal on Windows (":" mid-name) and a directory elsewhere - the delete throws for this one only
        Path notAFile = Files.createDirectories(tempDir.resolve("dir.xml").resolve("child"));
        ((GoodsInvoice) all.get(700)).setXmlFilePath(notAFile.getParent().toString());
        when(documentRepository.findPurgeCandidates(any(), anyString(), any())).thenAnswer(call -> {
            String afterId = call.getArgument(1);
            Pageable page = call.getArgument(2);
            return all.stream().filter(d -> d.getId().toString().compareTo(afterId) > 0).limit(page.getPageSize()).toList();
        });

        FiscalXmlPurgeScheduler.PurgeSummary summary = scheduler.purge();

        assertThat(summary.pages()).isEqualTo(3);
        assertThat(summary.candidates()).isEqualTo(1200);
        assertThat(summary.purged()).isEqualTo(1199);
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(all.get(700).isXmlPurged()).isFalse();
        assertThat(all.stream().filter(FiscalDocument::isXmlPurged).count()).isEqualTo(1199);
        verify(documentRepository, times(3)).findPurgeCandidates(any(), anyString(), any());
        verify(documentRepository, never()).save(all.get(700));
    }
}
