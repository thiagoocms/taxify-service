package br.com.cshub.taxify.service;

import br.com.cshub.taxify.support.TestNotes;
import br.com.cshub.taxify.integration.fiscalxml.FiscalEventData;
import br.com.cshub.taxify.integration.fiscalxml.FiscalEventParser;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.document.Document;
import br.com.cshub.taxify.dto.sieg.XmlDownloadResultDTO;
import br.com.cshub.taxify.enumerated.DocumentTypeEnum;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.integration.sieg.FiscalXmlFileStorage;
import br.com.cshub.taxify.integration.sieg.FiscalXmlMetadataParser;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlPackageExtractor;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncProperties;
import br.com.cshub.taxify.integration.sieg.SiegClient;
import br.com.cshub.taxify.integration.sieg.SiegProperties;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.domain.fiscaldocument.DocumentSourceKey;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocument;
import br.com.cshub.taxify.repository.FiscalDocumentRepository;
import br.com.cshub.taxify.repository.GoodsInvoiceRepository;
import br.com.cshub.taxify.support.InMemoryCaptureCounts;
import br.com.cshub.taxify.repository.FiscalXmlSyncControlRepository;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import br.com.cshub.taxify.domain.fiscaldocument.Cte;
import br.com.cshub.taxify.domain.fiscaldocument.GoodsInvoice;
import br.com.cshub.taxify.enumerated.CompanyDocumentRole;
import br.com.cshub.taxify.service.fiscaldocument.ImportLog;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.slf4j.LoggerFactory;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SiegXmlIntegrationServiceImportTest {

    private static final String NFE_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <nfeProc xmlns="http://www.portalfiscal.inf.br/nfe">
              <NFe>
                <infNFe Id="NFe35240112345678000199550010000000011000000010" versao="4.00">
                  <ide><mod>55</mod><dhEmi>2026-09-01T10:15:00-03:00</dhEmi></ide>
                  <emit><CNPJ>12345678000199</CNPJ></emit>
                  <dest><CNPJ>98765432000188</CNPJ></dest>
                </infNFe>
              </NFe>
            </nfeProc>
            """;

    @Mock
    private SiegClient siegClient;
    @Mock
    private FiscalDocumentRepository xmlDocumentRepository;
    @Mock
    private GoodsInvoiceRepository goodsInvoiceRepository;
    @Mock
    private FiscalXmlSyncControlRepository syncControlRepository;
    @Mock
    private CompanyValidation companyValidation;
    @Mock
    private MessageService messageService;
    @Mock
    private FiscalXmlEventService eventService;
    @Mock
    private FirmIntegrationService firmIntegrationService;
    @Mock
    private br.com.cshub.taxify.repository.SefazNoteSummaryRepository summaryRepository;
    @Mock
    private ServiceInvoiceService serviceInvoiceService;

    private FiscalXmlIntegrationService service;
    private UUID companyId;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        Company company = new Company(companyId);
        company.setDocument(new Document("11594485000105", DocumentTypeEnum.CNPJ));
        lenient().when(companyValidation.checkExist(companyId)).thenReturn(company);
        lenient().when(messageService.getMessage(any())).thenReturn("error");

        SiegProperties properties = new SiegProperties();

        FiscalXmlSyncProperties syncProperties = new FiscalXmlSyncProperties();
        syncProperties.setStoragePath(tempDir.toString());

        service = new FiscalXmlIntegrationService(siegClient, new FiscalXmlMetadataParser(),
                new FiscalXmlFileStorage(syncProperties, messageService), new FiscalXmlPackageExtractor(),
                xmlDocumentRepository, goodsInvoiceRepository, new InMemoryCaptureCounts().service(), syncControlRepository,
                companyValidation, messageService, properties, firmIntegrationService,
                new FiscalEventParser(), eventService, summaryRepository, serviceInvoiceService);
        lenient().when(xmlDocumentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void importsASingleXmlFile() {
        XmlDownloadResultDTO result = service.importManual(companyId.toString(), NFE_XML.getBytes(StandardCharsets.UTF_8));

        assertThat(result.totalPersistedNew()).isEqualTo(1);
        assertThat(result.totalDuplicatedSkipped()).isZero();
        // the upload answers with counts and keys - the XMLs themselves stay out of the response
        assertThat(result.xmls()).isEmpty();
        assertThat(result.persistedAccessKeys()).containsExactly("35240112345678000199550010000000011000000010");
    }

    @Test
    void importsEveryXmlInsideAZip() throws Exception {
        ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(zipBytes)) {
            zos.putNextEntry(new ZipEntry("one.xml"));
            zos.write(NFE_XML.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            String secondXml = NFE_XML.replace("35240112345678000199550010000000011000000010", "35240112345678000199550010000000021000000020");
            zos.putNextEntry(new ZipEntry("two.xml"));
            zos.write(secondXml.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        XmlDownloadResultDTO result = service.importManual(companyId.toString(), zipBytes.toByteArray());

        assertThat(result.totalPersistedNew()).isEqualTo(2);
        assertThat(result.persistedAccessKeys()).containsExactlyInAnyOrder(
                "35240112345678000199550010000000011000000010", "35240112345678000199550010000000021000000020");
    }

    @Test
    void eventXmlsGoToTheEventServiceAndAreNotCountedAsNotesOrErrors() {
        String cancellation = br.com.cshub.taxify.integration.fiscalxml.FiscalEventParserTest.procEvento(
                "110111", "<descEvento>Cancelamento</descEvento><xJust>Erro</xJust>", "135");
        when(eventService.record(any(), any())).thenReturn(true);

        XmlDownloadResultDTO result = service.importManual(companyId.toString(), cancellation.getBytes(StandardCharsets.UTF_8));

        assertThat(result.totalEventsRecorded()).isEqualTo(1);
        assertThat(result.totalPersistedNew()).isZero();
        assertThat(result.totalParseErrors()).isZero();
        verify(eventService).record(any(), org.mockito.ArgumentMatchers.<FiscalEventData>argThat(FiscalEventData::isCancellation));
    }

    /**
     * QA-02: a page of a blocked month - asked by issue date, the side's CNPJ and with events; stored like any SIEG note,
     * and the monthly sync bookkeeping is never touched (the current month's counts stay right).
     */
    @Test
    void aRecoveryPageStoresTheNotesWithoutTouchingTheSyncBookkeeping() {
        Company company = new Company(companyId);
        company.setDocument(new Document("11594485000105", DocumentTypeEnum.CNPJ));
        company.setAccountingFirm(new br.com.cshub.taxify.domain.accountingfirm.AccountingFirm(UUID.randomUUID()));
        when(companyValidation.checkExist(companyId)).thenReturn(company);
        when(siegClient.downloadXmls(any(), any())).thenReturn(java.util.List.of(NFE_XML.getBytes(StandardCharsets.UTF_8)));

        XmlDownloadResultDTO result = service.syncPeriodPageFromSieg(companyId,
                br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSide.RECEIVED,
                java.time.LocalDate.of(2026, 12, 1), java.time.LocalDate.of(2026, 12, 31), 2);

        assertThat(result.totalPersistedNew()).isEqualTo(1);
        org.mockito.ArgumentCaptor<br.com.cshub.taxify.integration.sieg.SiegDownloadXmlsRequest> request = org.mockito.ArgumentCaptor.captor();
        verify(siegClient).downloadXmls(request.capture(), any());
        assertThat(request.getValue().skip()).isEqualTo(100);
        assertThat(request.getValue().cnpjDest()).isEqualTo("11594485000105");
        assertThat(request.getValue().cnpjEmit()).isNull();
        org.mockito.Mockito.verifyNoInteractions(syncControlRepository);
    }

    /** QA-02 "sem duplicar notas": a note the regular sync already stored comes back in the recovery page - skipped. */
    @Test
    void aRecoveryPageNeverDuplicatesAStoredNote() {
        Company company = new Company(companyId);
        company.setDocument(new Document("11594485000105", DocumentTypeEnum.CNPJ));
        company.setAccountingFirm(new br.com.cshub.taxify.domain.accountingfirm.AccountingFirm(UUID.randomUUID()));
        when(companyValidation.checkExist(companyId)).thenReturn(company);
        when(siegClient.downloadXmls(any(), any())).thenReturn(java.util.List.of(NFE_XML.getBytes(StandardCharsets.UTF_8)));
        when(xmlDocumentRepository.findFirstByCompany_IdAndAccessKey(companyId, "35240112345678000199550010000000011000000010"))
                .thenReturn(java.util.Optional.of(TestNotes.note(company).key("35240112345678000199550010000000011000000010").build()));

        XmlDownloadResultDTO result = service.syncPeriodPageFromSieg(companyId,
                br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSide.RECEIVED,
                java.time.LocalDate.of(2026, 12, 1), java.time.LocalDate.of(2026, 12, 31), 0);

        assertThat(result.totalPersistedNew()).isZero();
        assertThat(result.totalDuplicatedSkipped()).isEqualTo(1);
        verify(xmlDocumentRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void skipsAnAlreadyPersistedAccessKeyAsDuplicate() {
        when(xmlDocumentRepository.findFirstByCompany_IdAndAccessKey(companyId, "35240112345678000199550010000000011000000010"))
                .thenReturn(java.util.Optional.of(TestNotes.note(TestNotes.company(TestNotes.COMPANY_CNPJ))
                        .key("35240112345678000199550010000000011000000010").build()));

        XmlDownloadResultDTO result = service.importManual(companyId.toString(), NFE_XML.getBytes(StandardCharsets.UTF_8));

        assertThat(result.totalPersistedNew()).isZero();
        assertThat(result.totalDuplicatedSkipped()).isEqualTo(1);
    }

    @Test
    void anUploadedNoteKeepsItsOriginAndIsNotCountedForEitherSource() {
        service.importManual(companyId.toString(), NFE_XML.getBytes(StandardCharsets.UTF_8));

        org.mockito.ArgumentCaptor<FiscalDocument> saved = org.mockito.ArgumentCaptor.captor();
        verify(xmlDocumentRepository).save(saved.capture());
        assertThat(saved.getValue().getSourceSystem()).isEqualTo(DocumentSourceKey.UPLOAD);
        assertThat(saved.getValue().firstSeenIn(DocumentSourceKey.SIEG)).isEmpty();
        assertThat(saved.getValue().firstSeenIn(DocumentSourceKey.SEFAZ_DFE)).isEmpty();
        // FD-RN-31: the upload is an origin too
        assertThat(saved.getValue().firstSeenIn(DocumentSourceKey.UPLOAD)).isPresent();
    }

    @Test
    void aSefazNoteCompletesItsSummaryAndCountsAsSeenBySefaz() {
        br.com.cshub.taxify.domain.xml.SefazNoteSummary summary = new br.com.cshub.taxify.domain.xml.SefazNoteSummary();
        when(summaryRepository.findByCompany_IdAndAccessKey(companyId, "35240112345678000199550010000000011000000010"))
                .thenReturn(java.util.Optional.of(summary));

        service.importFromSefaz(companyId, java.util.List.of(NFE_XML.getBytes(StandardCharsets.UTF_8)));

        org.mockito.ArgumentCaptor<FiscalDocument> saved = org.mockito.ArgumentCaptor.captor();
        verify(xmlDocumentRepository).save(saved.capture());
        assertThat(saved.getValue().getSourceSystem()).isEqualTo(DocumentSourceKey.SEFAZ_DFE);
        assertThat(saved.getValue().firstSeenIn(DocumentSourceKey.SEFAZ_DFE)).isPresent();
        assertThat(summary.isCompleted()).isTrue();
    }

    @Test
    void aNoteAlreadyStoredIsMarkedAsFoundByTheNewSource() {
        String key = "35240112345678000199550010000000011000000010";
        FiscalDocument known = TestNotes.note(TestNotes.company(TestNotes.COMPANY_CNPJ)).key(key).build();
        when(xmlDocumentRepository.findFirstByCompany_IdAndAccessKey(companyId, key)).thenReturn(java.util.Optional.of(known));

        XmlDownloadResultDTO result = service.importFromSefaz(companyId, java.util.List.of(NFE_XML.getBytes(StandardCharsets.UTF_8)));

        assertThat(result.totalDuplicatedSkipped()).isEqualTo(1);
        // FD-RN-31 + I-3: the new origin as one atomic upsert (SEFAZ DF-e, by cursor), the document itself untouched
        verify(xmlDocumentRepository).recordSeen(anyString(), eq(known.getId().toString()), eq(companyId.toString()),
                eq("SEFAZ_DFE"), eq("CURSOR"), any());
        verify(xmlDocumentRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void throwsWhenNoFileIsUploaded() {
        assertThatThrownBy(() -> service.importManual(companyId.toString(), new byte[0]))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void countsGarbageContentAsAParseErrorInsteadOfFailing() {
        XmlDownloadResultDTO result = service.importManual(companyId.toString(), "not xml at all".getBytes(StandardCharsets.UTF_8));

        assertThat(result.totalParseErrors()).isEqualTo(1);
        // FD-RN-03: a file that is no NF-e, NFC-e nor CT-e has no subtype in the new model - not stored (before: an
        // UNKNOWN row of the old table)
        assertThat(result.totalPersistedNew()).isZero();
    }

    @Test
    void aBigZipIsStoredInBatchesAndTheCountsAddUp() throws Exception {
        ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(zipBytes)) {
            for (int i = 0; i < 250; i++) {
                String key = String.format("352401123456780001995500100000%04d1000000010", i);
                zos.putNextEntry(new ZipEntry("nota-" + i + ".xml"));
                zos.write(NFE_XML.replace("35240112345678000199550010000000011000000010", key).getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }

        XmlDownloadResultDTO result = service.importManual(companyId.toString(), new java.io.ByteArrayInputStream(zipBytes.toByteArray()));

        // 3 batches (100 + 100 + 50) summed into one answer
        assertThat(result.totalXmlsCounted()).isEqualTo(250);
        assertThat(result.totalPersistedNew()).isEqualTo(250);
        assertThat(result.persistedAccessKeys()).hasSize(250).doesNotHaveDuplicates();
        assertThat(result.xmls()).isEmpty();
    }

    // ------------------------------------------------------------------ I-6: the import against the model's rules

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger importLog = (Logger) LoggerFactory.getLogger(ImportLog.class);

    @BeforeEach
    void captureTheImportLog() {
        logs.start();
        importLog.addAppender(logs);
    }

    @AfterEach
    void releaseTheImportLog() {
        importLog.detachAppender(logs);
    }

    private List<String> logLines(String event) {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).filter(line -> line.startsWith(event)).toList();
    }

    private static String nfe(String id, String model, String emit, String dest, String totals) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <nfeProc xmlns="http://www.portalfiscal.inf.br/nfe">
                  <NFe>
                    <infNFe Id="%s" versao="4.00">
                      <ide><mod>%s</mod><dhEmi>2026-11-10T10:15:00-03:00</dhEmi></ide>
                      <emit>%s</emit>
                      <dest>%s</dest>
                      %s
                    </infNFe>
                  </NFe>
                </nfeProc>
                """.formatted(id, model, emit, dest, totals);
    }

    private FiscalDocument saved() {
        org.mockito.ArgumentCaptor<FiscalDocument> saved = org.mockito.ArgumentCaptor.captor();
        verify(xmlDocumentRepository).save(saved.capture());
        return saved.getValue();
    }

    /** CA-FD-10: an NF-e without access key from SIEG is not created; the batch counts the failure and logs it. */
    @Test
    void ca10ASiegNfeWithoutKeyIsNotCreated() {
        String xml = nfe("", "55", "<CNPJ>12345678000195</CNPJ>", "<CNPJ>11594485000105</CNPJ>", "");

        XmlDownloadResultDTO result = service.importFromSefaz(companyId, List.of(xml.getBytes(StandardCharsets.UTF_8)));

        assertThat(result.totalPersistedNew()).isZero();
        assertThat(result.totalParseErrors()).isEqualTo(1);
        verify(xmlDocumentRepository, org.mockito.Mockito.never()).save(any());
        assertThat(logLines("fiscal.document.invariant.violated")).singleElement().asString()
                .contains("companyId=" + companyId, "type=NFE", "source=SEFAZ_DFE", "position=0", "keyHash=-");
    }

    /** CT-FD-21: an uploaded NFC-e with a key of 43 digits is not stored (I-11); never the raw key in the log. */
    @Test
    void ct21AnUploadWithAKeyOf43DigitsIsNotStored() {
        String key43 = "2624111234567800019565001000000001100000001";
        String xml = nfe("NFe" + key43, "65", "<CNPJ>12345678000195</CNPJ>", "<CPF>39053344705</CPF>", "");

        XmlDownloadResultDTO result = service.importManual(companyId.toString(), xml.getBytes(StandardCharsets.UTF_8));

        assertThat(result.totalPersistedNew()).isZero();
        assertThat(result.totalParseErrors()).isEqualTo(1);
        assertThat(logLines("fiscal.document.invariant.violated")).singleElement().asString()
                .contains("source=UPLOAD", "position=0").doesNotContain(key43).doesNotContain("12345678000195");
    }

    /** QA B3: an uploaded NF-e whose infNFe Id carries no key ("NFe") is not stored either (I-07, CA-FD-10). */
    @Test
    void anUploadWithAnEmptyKeyIsNotStored() {
        String xml = nfe("NFe", "55", "<CNPJ>12345678000195</CNPJ>", "<CNPJ>11594485000105</CNPJ>", "");

        XmlDownloadResultDTO result = service.importManual(companyId.toString(), xml.getBytes(StandardCharsets.UTF_8));

        assertThat(result.totalPersistedNew()).isZero();
        assertThat(result.totalParseErrors()).isEqualTo(1);
        verify(xmlDocumentRepository, org.mockito.Mockito.never()).save(any());
        assertThat(logLines("fiscal.document.invariant.violated")).singleElement().asString()
                .contains("invariant=I07_ACCESS_KEY", "source=UPLOAD", "keyHash=-");
    }

    /** I-17 (2026-11-28): a value with more places than the column is rounded HALF_UP, stored, and logged. */
    @Test
    void aDecimalWithMorePlacesIsRoundedAndTheNoteIsStored() {
        String xml = nfe("NFe35240112345678000199550010000000011000000010", "55", "<CNPJ>12345678000195</CNPJ>",
                "<CNPJ>11594485000105</CNPJ>", "<total><ICMSTot><vNF>100.005</vNF><vICMS>18.004</vICMS></ICMSTot></total>");

        XmlDownloadResultDTO result = service.importManual(companyId.toString(), xml.getBytes(StandardCharsets.UTF_8));

        assertThat(result.totalPersistedNew()).isEqualTo(1);
        GoodsInvoice stored = (GoodsInvoice) saved();
        assertThat(stored.getTotalAmount()).hasToString("100.01");
        assertThat(stored.getGoodsTotals().icmsAmount()).hasToString("18.00");
        assertThat(logLines("fiscal.document.decimal.rounded")).hasSize(2)
                .anyMatch(line -> line.contains("field=FiscalDocument.totalAmount") && line.contains("decimals=3"))
                .allMatch(line -> line.contains("companyId=" + companyId) && !line.contains("100.005"));
    }

    /** I-06 (2026-11-28): a CNPJ / CPF that lost its zeros on the left is completed by its tag's size. */
    @Test
    void aShortCnpjAndCpfAreCompletedByTheirTag() {
        String xml = nfe("NFe35240112345678000199550010000000011000000010", "55", "<CNPJ>1594485000105</CNPJ>",
                "<CPF>9053344705</CPF>", "");

        XmlDownloadResultDTO result = service.importManual(companyId.toString(), xml.getBytes(StandardCharsets.UTF_8));

        assertThat(result.totalPersistedNew()).isEqualTo(1);
        FiscalDocument stored = saved();
        assertThat(stored.getIssuer().cnpj()).isEqualTo("01594485000105");
        assertThat(stored.getRecipient().cpf()).isEqualTo("09053344705");
        assertThat(logLines("fiscal.document.party.padded")).hasSize(2)
                .anyMatch(line -> line.contains("field=Nfe.issuer.CNPJ") && line.contains("digits=13"))
                .anyMatch(line -> line.contains("field=Nfe.recipient.CPF") && line.contains("digits=10"))
                .noneMatch(line -> line.contains("1594485000105") || line.contains("9053344705"));
    }

    /** I-06: a document longer than its tag's size is refused (logged), the batch goes on. */
    @Test
    void aDocumentLongerThanItsTagIsRefused() {
        String xml = nfe("NFe35240112345678000199550010000000011000000010", "55", "<CNPJ>123456780001950</CNPJ>",
                "<CNPJ>11594485000105</CNPJ>", "");

        XmlDownloadResultDTO result = service.importManual(companyId.toString(), xml.getBytes(StandardCharsets.UTF_8));

        assertThat(result.totalPersistedNew()).isZero();
        assertThat(result.totalParseErrors()).isEqualTo(1);
        assertThat(logLines("fiscal.document.party.refused")).singleElement().asString()
                .contains("field=Nfe.issuer.CNPJ", "length=15");
        assertThat(logLines("fiscal.document.invariant.violated")).singleElement().asString().contains("invariant=I06");
    }

    private static String cte(String key, String recipient, String toma) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <cteProc xmlns="http://www.portalfiscal.inf.br/cte">
                  <CTe>
                    <infCte Id="CTe%s" versao="4.00">
                      <ide><mod>57</mod><dhEmi>2026-11-10T10:15:00-03:00</dhEmi>%s</ide>
                      <emit><CNPJ>12345678000195</CNPJ><xNome>TRANSPORTADORA FICTICIA</xNome></emit>
                      <rem><CNPJ>11444777000161</CNPJ><xNome>REMETENTE FICTICIO</xNome></rem>
                      <dest><CNPJ>%s</CNPJ><xNome>DESTINATARIO FICTICIO</xNome></dest>
                    </infCte>
                  </CTe>
                </cteProc>
                """.formatted(key, toma, recipient);
    }

    /** CT-FD-37: E1 is the recipient and not the taker - RECIPIENT, cte_is_recipient, no direction; in no group - THIRD_PARTY. */
    @Test
    void ct37ACteUploadedByItsRecipientOrByAThirdParty() {
        String own = "11594485000105";
        service.importManual(companyId.toString(), cte("26261112345678000195570010000000011000000010", own,
                "<toma3><toma>0</toma></toma3>").getBytes(StandardCharsets.UTF_8));
        Cte recipient = (Cte) saved();
        assertThat(recipient.getCompanyRole()).isEqualTo(CompanyDocumentRole.RECIPIENT);
        assertThat(recipient.getParticipation().recipient()).isTrue();
        assertThat(recipient.getParticipation().taker()).isFalse();
        assertThat(recipient.getDirection()).isNull();

        org.mockito.Mockito.clearInvocations(xmlDocumentRepository);
        service.importManual(companyId.toString(), cte("26261112345678000195570010000000021000000020", "99999999000191",
                "<toma3><toma>0</toma></toma3>").getBytes(StandardCharsets.UTF_8));
        Cte thirdParty = (Cte) saved();
        assertThat(thirdParty.getCompanyRole()).isEqualTo(CompanyDocumentRole.THIRD_PARTY);
        assertThat(thirdParty.getParticipation().hasAnyRole()).isFalse();
        assertThat(thirdParty.getDirection()).isNull();
    }
}
