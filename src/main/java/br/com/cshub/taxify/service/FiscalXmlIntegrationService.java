package br.com.cshub.taxify.service;

import br.com.cshub.taxify.config.ClockConfig;
import org.springframework.beans.factory.annotation.Autowired;
import br.com.cshub.taxify.common.IssuePeriod;
import java.util.Optional;
import br.com.cshub.taxify.integration.fiscalxml.FiscalEventParser;
import br.com.cshub.taxify.integration.fiscalxml.FiscalEventData;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSide;
import br.com.cshub.taxify.common.DocumentNumbers;
import br.com.cshub.taxify.enumerated.IntegrationType;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocumentItem;
import br.com.cshub.taxify.domain.xml.FiscalXmlHeaderExtras;
import br.com.cshub.taxify.domain.xml.FiscalXmlSyncControl;
import br.com.cshub.taxify.dto.fiscalxml.*;
import br.com.cshub.taxify.dto.sieg.XmlDownloadFilterDTO;
import br.com.cshub.taxify.dto.sieg.XmlDownloadResultDTO;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.exception.SiegIntegrationException;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlPackageExtractor;
import br.com.cshub.taxify.integration.sieg.*;
import br.com.cshub.taxify.repository.FiscalXmlDocumentRepository;
import br.com.cshub.taxify.repository.FiscalXmlSyncControlRepository;
import br.com.cshub.taxify.repository.SefazNoteSummaryRepository;
import br.com.cshub.taxify.enumerated.FiscalXmlSource;
import br.com.cshub.taxify.integration.nfse.NfseXml;
import br.com.cshub.taxify.integration.fiscalxml.NfeRetentions;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class FiscalXmlIntegrationService {

    /** Manual uploads are stored in batches of this many XMLs, the persistence context cleared in between. */
    static final int UPLOAD_BATCH = 100;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    private static final Logger log = LoggerFactory.getLogger(FiscalXmlIntegrationService.class);
    private static final int TIPO_XML_NFE = 1;
    /** SIEG's maximum notes per baixar-xmls page. */
    private static final int PAGE_SIZE = 50;
    /** A few days of notes is at most a few pages for any company - this just bounds a runaway loop. */
    private static final int EVENT_SWEEP_MAX_PAGES = 20;

    private final SiegClient siegClient;
    private final FiscalXmlMetadataParser metadataParser;
    private final FiscalXmlFileStorage fileStorage;
    private final FiscalXmlPackageExtractor packageExtractor;
    private final FiscalXmlDocumentRepository xmlDocumentRepository;
    private final FiscalXmlSyncControlRepository syncControlRepository;
    private final CompanyValidation companyValidation;
    private final MessageService messageService;
    private final SiegProperties properties;
    private final FirmIntegrationService firmIntegrationService;
    private final FiscalEventParser eventParser;
    private final FiscalXmlEventService eventService;
    private final SefazNoteSummaryRepository summaryRepository;
    private final ServiceInvoiceService serviceInvoiceService;
    /**
     * CP-RN-21: the verification times ({@code ultima_verificacao_em}, {@code issued_checked_at}) in America/Recife, like
     * the queue's {@code last_attempt_at} - never the JVM's zone (UTC in the container). The ClockConfig bean replaces it.
     */
    private Clock clock = Clock.system(ClockConfig.RECIFE);

    /** One SIEG side synced by the scheduler: the result and whether contar-xmls failed and the download went on (RN-07). */
    public record SiegSideOutcome(XmlDownloadResultDTO result, boolean countFailed) {
    }

    public FiscalXmlIntegrationService(SiegClient siegClient, FiscalXmlMetadataParser metadataParser,
                                       FiscalXmlFileStorage fileStorage, FiscalXmlPackageExtractor packageExtractor,
                                       FiscalXmlDocumentRepository xmlDocumentRepository, FiscalXmlSyncControlRepository syncControlRepository,
                                       CompanyValidation companyValidation, MessageService messageService, SiegProperties properties,
                                       FirmIntegrationService firmIntegrationService, FiscalEventParser eventParser,
                                       FiscalXmlEventService eventService, SefazNoteSummaryRepository summaryRepository,
                                       ServiceInvoiceService serviceInvoiceService) {
        this.serviceInvoiceService = serviceInvoiceService;
        this.summaryRepository = summaryRepository;
        this.firmIntegrationService = firmIntegrationService;
        this.eventParser = eventParser;
        this.eventService = eventService;
        this.siegClient = siegClient;
        this.metadataParser = metadataParser;
        this.fileStorage = fileStorage;
        this.packageExtractor = packageExtractor;
        this.xmlDocumentRepository = xmlDocumentRepository;
        this.syncControlRepository = syncControlRepository;
        this.companyValidation = companyValidation;
        this.messageService = messageService;
        this.properties = properties;
    }

    @Autowired(required = false)
    public void setClock(Clock clock) {
        if (clock != null) {
            this.clock = clock;
        }
    }

    /**
     * Calls SIEG's baixar-xmls and persists whatever comes back. Used only by
     * {@link SiegXmlSyncScheduler} now - the search endpoint reads from the database
     * instead, see {@link FiscalXmlQueryService#search(XmlSearchFilterDTO)}.
     *
     * <p>Checks contar-xmls first and skips the download entirely when it reports zero
     * pending NFe, so a company with nothing new doesn't spend a rate-limited
     * baixar-xmls call for an empty result. If the count check itself fails, that's
     * logged and treated as "unknown" - it does NOT block the download, since the count
     * is an optimization, not a precondition. Whenever the count check does succeed,
     * {@link FiscalXmlSyncControl} is updated so {@code GET /fiscal-xml-sync-control}
     * can show, per company, how many notes SIEG reports vs. how many are actually
     * persisted - "empresa X tem 200 notas na SIEG, só importou 100, faltam 100".
     */
    @Transactional
    public XmlDownloadResultDTO syncFromSieg(XmlDownloadFilterDTO filter) {
        return syncFromSieg(filter, false);
    }

    /**
     * {@code skipCountCheck}: when the caller already knows (from a previous check -
     * see {@link br.com.cshub.taxify.service.FiscalXmlSyncScheduler}) that there's more
     * to import than what's persisted so far, contar-xmls is skipped entirely and this
     * goes straight to baixar-xmls - one less rate-limited request for something already
     * established. {@link FiscalXmlSyncControl#getTotalSieg()} is left untouched in that
     * case (nothing new was learned about it); only {@code totalImportado} and
     * {@code ultimaVerificacaoEm} get refreshed.
     */
    @Transactional
    public XmlDownloadResultDTO syncFromSieg(XmlDownloadFilterDTO filter, boolean skipCountCheck) {
        Company company = findCompanyWithCnpj(filter.companyId());
        if (filter.side() != null) {
            return syncSide(company, filter, filter.side(), skipCountCheck);
        }
        // Manual "sincronizar" without a side: both, received first - each is counted apart.
        XmlDownloadResultDTO received = syncSide(company, filter, FiscalXmlSide.RECEIVED, skipCountCheck);
        XmlDownloadResultDTO issued = syncSide(company, filter, FiscalXmlSide.ISSUED, skipCountCheck);
        return merge(received, issued);
    }

    /**
     * The scheduler's call (one side, {@code filter.side()} required): same as {@link #syncFromSieg(XmlDownloadFilterDTO,
     * boolean)}, plus whether the count failed and the download went on anyway - the stage's warning
     * {@code SIEG_COUNT_FAILED} on the capture panel (CP-RN-10).
     */
    @Transactional
    public SiegSideOutcome syncSideFromSieg(XmlDownloadFilterDTO filter, boolean skipCountCheck) {
        Company company = findCompanyWithCnpj(filter.companyId());
        FiscalXmlSide side = filter.side() == null ? FiscalXmlSide.RECEIVED : filter.side();
        return syncSideOutcome(company, filter, side, skipCountCheck);
    }

    private XmlDownloadResultDTO syncSide(Company company, XmlDownloadFilterDTO filter, FiscalXmlSide side, boolean skipCountCheck) {
        return syncSideOutcome(company, filter, side, skipCountCheck).result();
    }

    /**
     * One side of the company: RECEIVED asks SIEG for notes where it's the recipient
     * ("CNPJdest"), ISSUED where it's the issuer ("CNPJemit").
     */
    private SiegSideOutcome syncSideOutcome(Company company, XmlDownloadFilterDTO filter, FiscalXmlSide side, boolean skipCountCheck) {
        String cnpj = DocumentNumbers.onlyDigits(company.getDocument().getNumber());
        SiegBaixarXmlsRequest request = new SiegBaixarXmlsRequest(
                filter.xmlType(),
                filter.take(),
                normalizeSkip(filter.skip()),
                startOfDayUtc(filter.dataEmissaoInicio()),
                endOfDayUtc(filter.dataEmissaoFim()),
                side == FiscalXmlSide.ISSUED ? cnpj : null,
                side == FiscalXmlSide.RECEIVED ? cnpj : null,
                true
        );

        String apiKey = firmIntegrationService.getApiKey(company.getAccountingFirm().getId(), IntegrationType.SIEG);

        if (skipCountCheck) {
            log.info("Skipping contar-xmls for company {} ({}) - already known pending from a previous check", company.getId(), side);
            List<byte[]> xmlContents = siegClient.baixarXmls(request, apiKey);
            XmlDownloadResultDTO result = persistAll(company, xmlContents, FiscalXmlSource.SIEG);
            // An empty page means the "still pending" from the last count no longer holds (notes
            // gone from SIEG, or counted but never downloadable): drop it, so the next tick counts
            // again instead of asking for this empty page forever.
            updateSyncControl(company, side, filter.dataEmissaoInicio(), filter.dataEmissaoFim(), null, xmlContents.isEmpty());
            return new SiegSideOutcome(result, false);
        }

        int totalSieg = countPendingNfe(company, request, apiKey);

        XmlDownloadResultDTO result;
        if (totalSieg == 0) {
            log.info("SIEG reports 0 pending NFe for company {} ({}) in this window, skipping baixar-xmls", company.getId(), side);
            result = new XmlDownloadResultDTO(0, 0, 0, 0, 0, List.of(), List.of(), 0);
        } else {
            List<byte[]> xmlContents = siegClient.baixarXmls(request, apiKey);
            result = persistAll(company, xmlContents, FiscalXmlSource.SIEG);
        }

        if (totalSieg >= 0) {
            updateSyncControl(company, side, filter.dataEmissaoInicio(), filter.dataEmissaoFim(), totalSieg);
        }

        return new SiegSideOutcome(result, totalSieg < 0);
    }

    private static XmlDownloadResultDTO merge(XmlDownloadResultDTO a, XmlDownloadResultDTO b) {
        List<String> keys = new ArrayList<>(a.persistedAccessKeys());
        keys.addAll(b.persistedAccessKeys());
        List<FiscalXmlItemDTO> xmls = new ArrayList<>(a.xmls());
        xmls.addAll(b.xmls());
        return new XmlDownloadResultDTO(a.totalItemsFromSieg() + b.totalItemsFromSieg(), a.totalXmlsCounted() + b.totalXmlsCounted(),
                a.totalPersistedNew() + b.totalPersistedNew(), a.totalDuplicatedSkipped() + b.totalDuplicatedSkipped(),
                a.totalParseErrors() + b.totalParseErrors(), keys, xmls, a.totalEventsRecorded() + b.totalEventsRecorded());
    }

    private int countPendingNfe(Company company, SiegBaixarXmlsRequest request, String apiKey) {
        try {
            // counted without BaixarEventos: the count must be notes only, comparable with what's stored
            SiegBaixarXmlsRequest countRequest = new SiegBaixarXmlsRequest(request.tipoXml(), request.take(), request.skip(),
                    request.dataEmissaoInicio(), request.dataEmissaoFim(), request.cnpjEmit(), request.cnpjDest(), null);
            return siegClient.contarXmlsNfe(countRequest, apiKey);
        } catch (Exception e) {
            log.warn("Could not count pending SIEG NFe for company {} - proceeding with baixar-xmls anyway", company.getId(), e);
            return -1;
        }
    }

    /**
     * {@code totalSieg} is nullable: null means "not re-checked this call" (see the
     * {@code skipCountCheck} path above) - the existing value on the row is left as-is,
     * while {@code totalImportado} and {@code ultimaVerificacaoEm} still get refreshed to
     * reflect whatever baixar-xmls just persisted.
     */
    private void updateSyncControl(Company company, FiscalXmlSide side, LocalDate dataEmissaoInicio, LocalDate dataEmissaoFim,
                                   Integer totalSieg) {
        updateSyncControl(company, side, dataEmissaoInicio, dataEmissaoFim, totalSieg, false);
    }

    /** @param clearPending sets SIEG's total to what is imported - nothing known pending anymore */
    private void updateSyncControl(Company company, FiscalXmlSide side, LocalDate dataEmissaoInicio, LocalDate dataEmissaoFim,
                                   Integer totalSieg, boolean clearPending) {
        FiscalXmlSyncControl control = syncControlRepository.findByCompany_Id(company.getId())
                .orElseGet(() -> {
                    FiscalXmlSyncControl created = new FiscalXmlSyncControl();
                    created.setCompany(company);
                    created.setDeleted(false);
                    created.setUltimaVerificacaoEm(LocalDateTime.now(clock));
                    return created;
                });
        LocalDateTime from = IssuePeriod.start(dataEmissaoInicio);
        LocalDateTime to = IssuePeriod.end(dataEmissaoFim);

        int imported = (int) xmlDocumentRepository.countByCompany_IdAndOwnIssueAndIssueDateBetween(
                company.getId(), side.ownIssue(), from, to);
        closePreviousMonth(control, dataEmissaoInicio);
        control.setDataEmissaoInicio(dataEmissaoInicio);
        control.setDataEmissaoFim(dataEmissaoFim);
        if (clearPending) {
            totalSieg = imported;
        }
        if (side == FiscalXmlSide.ISSUED) {
            if (totalSieg != null) {
                control.setIssuedTotalSieg(totalSieg);
            }
            control.setIssuedTotalImported(imported);
            control.setIssuedCheckedAt(LocalDateTime.now(clock));
        } else {
            if (totalSieg != null) {
                control.setTotalSieg(totalSieg);
            }
            control.setTotalImportado(imported);
            control.setUltimaVerificacaoEm(LocalDateTime.now(clock));
        }
        syncControlRepository.save(control);
    }

    /**
     * CP-RN-22: the window moves to a later month - each side's counters are still the old month's, so they are kept as
     * "how that month closed" before being replaced. The window is shared by both sides: the first side to write the new
     * month closes both. A side last checked before the old window (stale counters, or never checked) is not closed. A
     * manual sync of an earlier month (window going back) closes nothing.
     */
    static void closePreviousMonth(FiscalXmlSyncControl control, LocalDate newWindowStart) {
        LocalDate oldStart = control.getDataEmissaoInicio();
        if (oldStart == null || newWindowStart == null || !YearMonth.from(newWindowStart).isAfter(YearMonth.from(oldStart))) {
            return;
        }
        String month = YearMonth.from(oldStart).toString();
        // one day of margin: the window's day comes from the JVM's zone, the verification time from Recife's
        LocalDate checkedFrom = oldStart.minusDays(1);
        if (control.getUltimaVerificacaoEm() != null && !control.getUltimaVerificacaoEm().toLocalDate().isBefore(checkedFrom)) {
            control.closeReceivedMonth(month, control.getTotalSieg(), control.getTotalImportado());
        }
        if (control.getIssuedCheckedAt() != null && !control.getIssuedCheckedAt().toLocalDate().isBefore(checkedFrom)) {
            control.closeIssuedMonth(month, control.getIssuedTotalSieg(), control.getIssuedTotalImported());
        }
    }

    /**
     * Manual counterpart to {@link #syncFromSieg}: lets someone upload fiscal XMLs by
     * hand (a single XML, or a zip of several - {@link FiscalXmlPackageExtractor} tells
     * them apart) instead of waiting on SIEG. Same persistence path either way, so a
     * manually imported note is indistinguishable from a synced one afterward, except
     * {@code source_system} stays whatever {@link #toDocument} sets it to.
     */
    @Transactional
    public XmlDownloadResultDTO importManual(String companyId, byte[] uploadedBytes) {
        if (uploadedBytes == null || uploadedBytes.length == 0) {
            findCompanyWithCnpj(companyId);
            throw new BadRequestException("error.fiscalXml.import.empty.file", messageService.getMessage("error.fiscalXml.import.empty.file"));
        }
        return importManual(companyId, new java.io.ByteArrayInputStream(uploadedBytes));
    }

    /**
     * The upload read as a stream - one XML in memory at a time, stored in batches of
     * {@link #UPLOAD_BATCH} with the persistence context cleared in between, so a big zip doesn't
     * pile up in the heap. The answer carries the counts and keys, not the XMLs themselves.
     */
    @Transactional
    public XmlDownloadResultDTO importManual(String companyId, java.io.InputStream upload) {
        Company company = findCompanyWithCnpj(companyId);
        UUID id = company.getId();
        List<byte[]> batch = new ArrayList<>();
        XmlDownloadResultDTO[] total = {null};
        int found;
        try {
            found = packageExtractor.forEachXml(upload, xml -> {
                batch.add(xml);
                if (batch.size() >= UPLOAD_BATCH) {
                    total[0] = plus(total[0], persistAll(companyForBatch(id), batch, FiscalXmlSource.UPLOAD, false));
                    batch.clear();
                    releaseMemory();
                }
            });
        } catch (java.io.IOException e) {
            throw new BadRequestException("error.fiscalXml.import.no.xml.found", messageService.getMessage("error.fiscalXml.import.no.xml.found"));
        }
        if (found == 0) {
            throw new BadRequestException("error.fiscalXml.import.no.xml.found", messageService.getMessage("error.fiscalXml.import.no.xml.found"));
        }
        if (!batch.isEmpty()) {
            total[0] = plus(total[0], persistAll(companyForBatch(id), batch, FiscalXmlSource.UPLOAD, false));
        }
        return total[0];
    }

    /** The company again after a {@link #releaseMemory} - the old instance is detached. */
    private Company companyForBatch(UUID companyId) {
        return companyValidation.checkExist(companyId);
    }

    /** Writes what's pending and forgets the entities loaded so far (only the persistence context, not the data). */
    private void releaseMemory() {
        if (entityManager != null) {
            entityManager.flush();
            entityManager.clear();
        }
    }

    private static XmlDownloadResultDTO plus(XmlDownloadResultDTO a, XmlDownloadResultDTO b) {
        if (a == null) {
            return b;
        }
        List<String> keys = new ArrayList<>(a.persistedAccessKeys());
        keys.addAll(b.persistedAccessKeys());
        List<FiscalXmlItemDTO> items = new ArrayList<>(a.xmls());
        items.addAll(b.xmls());
        return new XmlDownloadResultDTO(a.totalItemsFromSieg() + b.totalItemsFromSieg(), a.totalXmlsCounted() + b.totalXmlsCounted(),
                a.totalPersistedNew() + b.totalPersistedNew(), a.totalDuplicatedSkipped() + b.totalDuplicatedSkipped(),
                a.totalParseErrors() + b.totalParseErrors(), keys, items, a.totalEventsRecorded() + b.totalEventsRecorded());
    }

    /**
     * Full notes (procNFe) and events (procEventoNFe) brought by the SEFAZ distribution
     * ({@link SefazDistService}) - same persistence as SIEG and manual uploads, so a note
     * that came from more than one source is stored once.
     */
    @Transactional
    public XmlDownloadResultDTO importFromSefaz(UUID companyId, List<byte[]> xmlContents) {
        return persistAll(findCompanyWithCnpj(companyId.toString()), xmlContents, FiscalXmlSource.SEFAZ_DIST);
    }

    /**
     * Stores what a provider page (or a manual upload) brought: SEFAZ events go to
     * FiscalXmlEventService, notes are persisted once per company - the same note may
     * legitimately belong to two clients of the firm (one's exit, the other's entry).
     * {@code source} is kept on a new note and marks, on new and known notes alike, that this
     * source has it (the SIEG x SEFAZ comparison); a SEFAZ summary of the note is completed.
     */
    private XmlDownloadResultDTO persistAll(Company company, List<byte[]> xmlContents, FiscalXmlSource source) {
        return persistAll(company, xmlContents, source, true);
    }

    /** @param withItems whether the answer lists each note with its XML (the SIEG search screen) or only counts and keys */
    private XmlDownloadResultDTO persistAll(Company company, List<byte[]> xmlContents, FiscalXmlSource source, boolean withItems) {
        int persistedNew = 0;
        int duplicated = 0;
        int parseErrors = 0;
        int events = 0;
        int notes = 0;
        List<String> persistedKeys = new ArrayList<>();
        List<FiscalXmlItemDTO> xmls = new ArrayList<>();

        for (byte[] xmlBytes : xmlContents) {
            Optional<FiscalEventData> event = eventParser.parse(xmlBytes);
            if (event.isPresent()) {
                if (eventService.record(company, event.get())) {
                    events++;
                }
                continue;
            }
            notes++;

            // a service invoice (NFS-e) goes to its own table - it has no NF-e fields to fill
            if (NfseXml.looksLikeNfse(xmlBytes)) {
                switch (serviceInvoiceService.store(company, xmlBytes, source.name())) {
                    case NEW -> persistedNew++;
                    case DUPLICATE -> duplicated++;
                    case INVALID -> parseErrors++;
                }
                continue;
            }

            FiscalXmlMetadata metadata = metadataParser.parse(xmlBytes);
            String xmlContent = new String(xmlBytes, StandardCharsets.UTF_8);

            if ("UNKNOWN".equals(metadata.documentType()) && metadata.accessKey() == null) {
                parseErrors++;
            }

            if (metadata.accessKey() != null && xmlDocumentRepository.existsByCompany_IdAndAccessKey(company.getId(), metadata.accessKey())) {
                duplicated++;
                xmlDocumentRepository.findFirstByCompany_IdAndAccessKey(company.getId(), metadata.accessKey())
                        .filter(known -> markFoundIn(known, source))
                        .ifPresent(xmlDocumentRepository::save);
                if (withItems) {
                    xmls.add(toItemDTO(metadata, xmlContent));
                }
                continue;
            }

            String xmlFilePath = fileStorage.store(company.getId(), metadata.accessKey(), xmlBytes);
            FiscalXmlDocument fresh = toDocument(metadata, company, xmlFilePath);
            NfeRetentions.parse(xmlBytes).applyTo(fresh);
            fresh.setSourceSystem(source.name());
            markFoundIn(fresh, source);
            completeSummary(company, fresh);
            FiscalXmlDocument document = xmlDocumentRepository.save(fresh);
            eventService.linkPendingEvents(document);
            persistedNew++;
            if (metadata.accessKey() != null) {
                persistedKeys.add(metadata.accessKey());
            }
            if (withItems) {
                xmls.add(toItemDTO(metadata, xmlContent));
            }
        }

        return new XmlDownloadResultDTO(
                xmlContents.size(),
                notes,
                persistedNew,
                duplicated,
                parseErrors,
                persistedKeys,
                xmls,
                events
        );
    }

    /** @return whether the note changed - the first time this source brings it */
    private static boolean markFoundIn(FiscalXmlDocument document, FiscalXmlSource source) {
        LocalDateTime now = LocalDateTime.now();
        if (source == FiscalXmlSource.SIEG && document.getFoundInSiegAt() == null) {
            document.setFoundInSiegAt(now);
            return true;
        }
        if (source == FiscalXmlSource.SEFAZ_DIST && document.getFoundInSefazAt() == null) {
            document.setFoundInSefazAt(now);
            return true;
        }
        return false;
    }

    /**
     * A SEFAZ summary of this note (an entry waiting for Ciência) is done now that the full XML is
     * here, whichever source brought it - and SEFAZ counts as having had the note since then.
     */
    private void completeSummary(Company company, FiscalXmlDocument document) {
        if (document.getAccessKey() == null) {
            return;
        }
        summaryRepository.findByCompany_IdAndAccessKey(company.getId(), document.getAccessKey())
                .filter(summary -> !summary.isCompleted())
                .ifPresent(summary -> {
                    summary.setCompletedAt(LocalDateTime.now());
                    summaryRepository.save(summary);
                    if (document.getFoundInSefazAt() == null) {
                        document.setFoundInSefazAt(summary.getCreatedDate() != null ? summary.getCreatedDate() : LocalDateTime.now());
                    }
                });
    }

    /**
     * Re-reads the notes of a recent window with their events, so a cancellation that
     * happened after the note was imported is still caught (a note can be canceled up to
     * 24h after authorization, the regular sync has usually imported it by then). Pages from
     * the start; known notes come back as duplicates and cost nothing but the request. Never
     * touches the sync bookkeeping - the monthly counts stay comparable.
     *
     * @return new events stored
     */
    @Transactional
    public int refreshRecentEvents(UUID companyId, FiscalXmlSide side, LocalDate from, LocalDate to) {
        Company company = findCompanyWithCnpj(companyId.toString());
        String cnpj = DocumentNumbers.onlyDigits(company.getDocument().getNumber());
        String apiKey = firmIntegrationService.getApiKey(company.getAccountingFirm().getId(), IntegrationType.SIEG);
        int events = 0;
        for (int page = 0; page < EVENT_SWEEP_MAX_PAGES; page++) {
            SiegBaixarXmlsRequest request = new SiegBaixarXmlsRequest(TIPO_XML_NFE, PAGE_SIZE, page * PAGE_SIZE,
                    startOfDayUtc(from), endOfDayUtc(to),
                    side == FiscalXmlSide.ISSUED ? cnpj : null,
                    side == FiscalXmlSide.RECEIVED ? cnpj : null,
                    true);
            XmlDownloadResultDTO result = persistAll(company, siegClient.baixarXmls(request, apiKey), FiscalXmlSource.SIEG);
            events += result.totalEventsRecorded();
            if (result.totalXmlsCounted() < PAGE_SIZE) {
                break;
            }
        }
        return events;
    }

    /**
     * Recovery of a blocked period (QA-02): one page ({@code page} × PAGE_SIZE) of the side's notes issued in
     * [from, to], with their events, stored like any SIEG note - known keys are skipped, so asking again never duplicates.
     * Never touches the sync bookkeeping: the current month's counts stay comparable.
     *
     * @return the page's result; fewer than PAGE_SIZE notes counted = last page
     */
    @Transactional
    public XmlDownloadResultDTO syncPeriodPageFromSieg(UUID companyId, FiscalXmlSide side, LocalDate from, LocalDate to, int page) {
        Company company = findCompanyWithCnpj(companyId.toString());
        String cnpj = DocumentNumbers.onlyDigits(company.getDocument().getNumber());
        String apiKey = firmIntegrationService.getApiKey(company.getAccountingFirm().getId(), IntegrationType.SIEG);
        SiegBaixarXmlsRequest request = new SiegBaixarXmlsRequest(TIPO_XML_NFE, PAGE_SIZE, page * PAGE_SIZE,
                startOfDayUtc(from), endOfDayUtc(to),
                side == FiscalXmlSide.ISSUED ? cnpj : null,
                side == FiscalXmlSide.RECEIVED ? cnpj : null,
                true);
        // counts and keys only - the recovery never answers with the XMLs themselves
        return persistAll(company, siegClient.baixarXmls(request, apiKey), FiscalXmlSource.SIEG, false);
    }

    public static int pageSize() {
        return PAGE_SIZE;
    }

    /**
     * Same company/period lookup as {@link FiscalXmlQueryService#search}, but serves each note's
     * rectified version ({@link FiscalXmlDocument#getRectifiedFilePath()}) instead of the
     * original whenever one exists - so an exported nota reflects the corrected CFOP/NCM
     * a {@link FiscalXmlRectificationService} run applied, not what SEFAZ originally
     * issued. A note never rectified simply falls back to {@code xmlFilePath}.
     */
    @Transactional(readOnly = true)
    public List<FiscalXmlItemDTO> exportXmls(XmlDownloadFilterDTO filter) {
        Company company = findCompanyWithCnpj(filter.companyId());

        LocalDateTime from = IssuePeriod.start(filter.dataEmissaoInicio());
        LocalDateTime to = IssuePeriod.end(filter.dataEmissaoFim());

        List<FiscalXmlDocument> documents = xmlDocumentRepository
                .findByCompany_IdAndIssueDateBetweenOrderByIssueDateDesc(company.getId(), from, to);

        List<FiscalXmlItemDTO> xmls = new ArrayList<>();
        for (FiscalXmlDocument document : documents) {
            String path = document.getRectifiedFilePath() != null ? document.getRectifiedFilePath() : document.getXmlFilePath();
            try {
                byte[] xmlBytes = fileStorage.read(path);
                FiscalXmlMetadata metadata = metadataParser.parse(xmlBytes);
                xmls.add(toItemDTO(metadata, new String(xmlBytes, StandardCharsets.UTF_8)));
            } catch (IOException e) {
                log.warn("Could not read stored XML file '{}' for document {} during export", path, document.getId(), e);
            }
        }
        return xmls;
    }

    /**
     * Same company/period/rectified-vs-original resolution as {@link #exportXmls}, but
     * bundles the raw XML files themselves into a .zip instead of parsing them into JSON
     * - one entry per note, named by access key (falls back to the document id when
     * there isn't one). A file that can't be read is skipped (logged), same as
     * {@link #exportXmls}, rather than failing the whole export.
     */
    @Transactional(readOnly = true)
    public byte[] exportXmlsZip(XmlDownloadFilterDTO filter) {
        Company company = findCompanyWithCnpj(filter.companyId());

        LocalDateTime from = IssuePeriod.start(filter.dataEmissaoInicio());
        LocalDateTime to = IssuePeriod.end(filter.dataEmissaoFim());

        List<FiscalXmlDocument> documents = xmlDocumentRepository
                .findByCompany_IdAndIssueDateBetweenOrderByIssueDateDesc(company.getId(), from, to);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Set<String> usedNames = new HashSet<>();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (FiscalXmlDocument document : documents) {
                String path = document.getRectifiedFilePath() != null ? document.getRectifiedFilePath() : document.getXmlFilePath();
                byte[] xmlBytes;
                try {
                    xmlBytes = fileStorage.read(path);
                } catch (IOException e) {
                    log.warn("Could not read stored XML file '{}' for document {} during zip export", path, document.getId(), e);
                    continue;
                }
                zip.putNextEntry(new ZipEntry(uniqueEntryName(document, usedNames)));
                zip.write(xmlBytes);
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new SiegIntegrationException("error.sieg.xml.storage.failed", messageService.getMessage("error.sieg.xml.storage.failed"), e);
        }
        return buffer.toByteArray();
    }

    private String uniqueEntryName(FiscalXmlDocument document, Set<String> usedNames) {
        String base = document.accessKeyOrId();
        String name = base + ".xml";
        int suffix = 2;
        while (!usedNames.add(name)) {
            name = base + "-" + suffix++ + ".xml";
        }
        return name;
    }

    private FiscalXmlDocument toDocument(FiscalXmlMetadata metadata, Company company, String xmlFilePath) {
        FiscalXmlDocument document = new FiscalXmlDocument();
        document.setCompany(company);
        document.setAccessKey(metadata.accessKey());
        document.setDocumentType(metadata.documentType());
        document.setIssuerCnpj(metadata.issuerCnpj());
        document.setRecipientCnpj(metadata.recipientCnpj());
        document.setIssueDate(metadata.issueDate());
        document.setXmlFilePath(xmlFilePath);
        document.setMetadataVersion(FiscalXmlMetadataParser.CURRENT_VERSION);
        document.setDeleted(false);
        String issuerCpf = metadata.emitente() == null ? null : metadata.emitente().cpf();
        String tipoOperacao = metadata.identificacao() == null ? null : metadata.identificacao().tipoOperacao();
        boolean ownIssue = FiscalDirections.isOwnIssue(company.getDocument().getNumber(), metadata.issuerCnpj(), issuerCpf);
        document.setOwnIssue(ownIssue);
        document.setDirection(FiscalDirections.directionOf(ownIssue, tipoOperacao));

        applyIdentificacao(document, metadata.identificacao());
        applyEmitente(document, metadata.emitente());
        applyDestinatario(document, metadata.destinatario());
        applyTotais(document, metadata.totais());
        document.setHeaderExtras(FiscalXmlHeaderExtras.of(metadata));

        for (FiscalXmlProdutoDTO produto : metadata.produtos()) {
            document.getItens().add(toItemEntity(produto, document));
        }

        return document;
    }

    private void applyIdentificacao(FiscalXmlDocument document, FiscalXmlIdentificacaoDTO ide) {
        if (ide == null) {
            return;
        }
        document.setCodigoUf(ide.codigoUf());
        document.setNaturezaOperacao(ide.naturezaOperacao());
        document.setModelo(ide.modelo());
        document.setSerie(ide.serie());
        document.setNumero(ide.numero());
        document.setDataSaidaEntrada(ide.dataSaidaEntrada());
        document.setTipoOperacao(ide.tipoOperacao());
        document.setDestinoOperacao(ide.destinoOperacao());
        document.setCodigoMunicipioFatoGerador(ide.codigoMunicipioFatoGerador());
        document.setAmbiente(ide.ambiente());
        document.setFinalidadeEmissao(ide.finalidadeEmissao());
        document.setConsumidorFinal(ide.consumidorFinal());
        document.setIndicadorPresenca(ide.indicadorPresenca());
    }

    private void applyEmitente(FiscalXmlDocument document, FiscalXmlEmitenteDTO emit) {
        if (emit == null) {
            return;
        }
        document.setEmitenteCpf(emit.cpf());
        document.setEmitenteNome(emit.nome());
        document.setEmitenteNomeFantasia(emit.nomeFantasia());
        document.setEmitenteInscricaoEstadual(emit.inscricaoEstadual());
        document.setEmitenteInscricaoEstadualSubstitutoTributario(emit.inscricaoEstadualSubstitutoTributario());
        document.setEmitenteInscricaoMunicipal(emit.inscricaoMunicipal());
        document.setEmitenteCnae(emit.cnae());
        document.setEmitenteRegimeTributario(emit.regimeTributario());

        FiscalXmlEnderecoDTO endereco = emit.endereco();
        if (endereco != null) {
            document.setEmitenteLogradouro(endereco.logradouro());
            document.setEmitenteEnderecoNumero(endereco.numero());
            document.setEmitenteComplemento(endereco.complemento());
            document.setEmitenteBairro(endereco.bairro());
            document.setEmitenteCodigoMunicipio(endereco.codigoMunicipio());
            document.setEmitenteMunicipio(endereco.municipio());
            document.setEmitenteUf(endereco.uf());
            document.setEmitenteCep(endereco.cep());
            document.setEmitenteTelefone(endereco.telefone());
        }
    }

    private void applyDestinatario(FiscalXmlDocument document, FiscalXmlDestinatarioDTO dest) {
        if (dest == null) {
            return;
        }
        document.setDestinatarioCpf(dest.cpf());
        document.setDestinatarioIdEstrangeiro(dest.idEstrangeiro());
        document.setDestinatarioNome(dest.nome());
        document.setDestinatarioInscricaoEstadual(dest.inscricaoEstadual());
        document.setDestinatarioIndicadorIe(dest.indicadorIe());
        document.setDestinatarioEmail(dest.email());

        FiscalXmlEnderecoDTO endereco = dest.endereco();
        if (endereco != null) {
            document.setDestinatarioLogradouro(endereco.logradouro());
            document.setDestinatarioEnderecoNumero(endereco.numero());
            document.setDestinatarioComplemento(endereco.complemento());
            document.setDestinatarioBairro(endereco.bairro());
            document.setDestinatarioCodigoMunicipio(endereco.codigoMunicipio());
            document.setDestinatarioMunicipio(endereco.municipio());
            document.setDestinatarioUf(endereco.uf());
            document.setDestinatarioCep(endereco.cep());
            document.setDestinatarioTelefone(endereco.telefone());
        }
    }

    private void applyTotais(FiscalXmlDocument document, FiscalXmlTotaisDTO totais) {
        if (totais == null) {
            return;
        }
        document.setBaseCalculoIcms(totais.baseCalculoIcms());
        document.setValorIcms(totais.valorIcms());
        document.setValorIcmsDesonerado(totais.valorIcmsDesonerado());
        document.setValorFcp(totais.valorFcp());
        document.setBaseCalculoIcmsSt(totais.baseCalculoIcmsSt());
        document.setValorIcmsSt(totais.valorIcmsSt());
        document.setValorFcpSt(totais.valorFcpSt());
        document.setValorProdutos(totais.valorProdutos());
        document.setValorFrete(totais.valorFrete());
        document.setValorSeguro(totais.valorSeguro());
        document.setValorDesconto(totais.valorDesconto());
        document.setValorImportacao(totais.valorImportacao());
        document.setValorIpi(totais.valorIpi());
        document.setValorPis(totais.valorPis());
        document.setValorCofins(totais.valorCofins());
        document.setValorOutrasDespesas(totais.valorOutrasDespesas());
        document.setValorTotalNota(totais.valorTotalNota());
        document.setValorAproximadoTributos(totais.valorAproximadoTributos());
        document.setIbsCbsTotals(totais.ibsCbs());
    }

    private FiscalXmlDocumentItem toItemEntity(FiscalXmlProdutoDTO produto, FiscalXmlDocument document) {
        FiscalXmlDocumentItem item = new FiscalXmlDocumentItem();
        item.setDocument(document);
        item.setNumeroItem(produto.numeroItem());
        item.setCodigo(produto.codigo());
        item.setCodigoBarras(produto.codigoBarras());
        item.setDescricao(produto.descricao());
        item.setNcm(produto.ncm());
        item.setCfop(produto.cfop());
        item.setUnidadeComercial(produto.unidadeComercial());
        item.setQuantidadeComercial(produto.quantidadeComercial());
        item.setValorUnitarioComercial(produto.valorUnitarioComercial());
        item.setValorTotal(produto.valorTotal());
        item.setUnidadeTributavel(produto.unidadeTributavel());
        item.setQuantidadeTributavel(produto.quantidadeTributavel());
        item.setValorUnitarioTributavel(produto.valorUnitarioTributavel());

        FiscalXmlImpostoItemDTO impostos = produto.impostos();
        if (impostos != null) {
            item.setSituacaoTributariaIcms(impostos.situacaoTributariaIcms());
            item.setBaseCalculoIcms(impostos.baseCalculoIcms());
            item.setAliquotaIcms(impostos.aliquotaIcms());
            item.setValorIcms(impostos.valorIcms());
            item.setSituacaoTributariaIpi(impostos.situacaoTributariaIpi());
            item.setValorIpi(impostos.valorIpi());
            item.setSituacaoTributariaPis(impostos.situacaoTributariaPis());
            item.setValorPis(impostos.valorPis());
            item.setSituacaoTributariaCofins(impostos.situacaoTributariaCofins());
            item.setValorCofins(impostos.valorCofins());
            item.setTaxDetail(impostos.detalhe());
            item.setIbsCbs(impostos.ibsCbs());
        }

        return item;
    }

    private FiscalXmlItemDTO toItemDTO(FiscalXmlMetadata metadata, String xmlContent) {
        return new FiscalXmlItemDTO(
                metadata.accessKey(),
                metadata.documentType(),
                metadata.issuerCnpj(),
                metadata.recipientCnpj(),
                metadata.issueDate(),
                metadata.identificacao(),
                metadata.emitente(),
                metadata.destinatario(),
                metadata.totais(),
                metadata.produtos(),
                xmlContent
        );
    }

    private Company findCompanyWithCnpj(String companyId) {
        UUID id;
        try {
            id = UUID.fromString(companyId);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("error.generic.bad.request", messageService.getMessage("error.generic.bad.request"));
        }

        return companyValidation.checkExist(id);
    }

    private Integer normalizeSkip(Integer skip) {
        return (skip == null || skip < 0) ? 0 : skip;
    }

    private Instant startOfDayUtc(LocalDate date) {
        return date == null ? null : date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private Instant endOfDayUtc(LocalDate date) {
        return date == null ? null : date.atTime(LocalTime.MAX).atZone(ZoneOffset.UTC).toInstant();
    }
}
