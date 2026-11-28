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
import br.com.cshub.taxify.domain.fiscaldocument.CaptureCounts;
import br.com.cshub.taxify.domain.fiscaldocument.CapturePeriodCount;
import br.com.cshub.taxify.domain.fiscaldocument.DocumentSourceKey;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocument;
import br.com.cshub.taxify.domain.fiscaldocument.GoodsInvoice;
import br.com.cshub.taxify.domain.fiscaldocument.ImportAdjustments;
import br.com.cshub.taxify.service.fiscaldocument.ImportLog;
import br.com.cshub.taxify.domain.xml.FiscalXmlSyncControl;
import br.com.cshub.taxify.enumerated.CaptureSide;
import br.com.cshub.taxify.enumerated.InvariantScope;
import br.com.cshub.taxify.enumerated.RetrievalMode;
import br.com.cshub.taxify.exception.FiscalDocumentInvariantException;
import br.com.cshub.taxify.mapper.fiscaldocument.FiscalDocumentFactory;
import br.com.cshub.taxify.mapper.fiscaldocument.LegacySourceNames;
import br.com.cshub.taxify.repository.FiscalDocumentRepository;
import br.com.cshub.taxify.repository.GoodsInvoiceRepository;
import br.com.cshub.taxify.service.capture.CaptureCountsService;
import br.com.cshub.taxify.dto.fiscalxml.*;
import br.com.cshub.taxify.dto.sieg.XmlDownloadFilterDTO;
import br.com.cshub.taxify.dto.sieg.XmlDownloadResultDTO;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.exception.SiegIntegrationException;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlPackageExtractor;
import br.com.cshub.taxify.integration.sieg.*;
import br.com.cshub.taxify.repository.FiscalXmlSyncControlRepository;
import br.com.cshub.taxify.repository.SefazNoteSummaryRepository;
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
    private final FiscalDocumentRepository documentRepository;
    private final GoodsInvoiceRepository goodsInvoiceRepository;
    private final CaptureCountsService countsService;
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

    /**
     * One SIEG side synced by the scheduler: the result, whether contar-xmls failed and the download went on (RN-07), the
     * NFS-e count the same contar-xmls answered (null = not counted) and the reconciliation the side needs (null = none) -
     * run by {@link SiegReconciliationService} outside this transaction (B1).
     */
    public record SiegSideOutcome(XmlDownloadResultDTO result, boolean countFailed, Integer serviceInvoiceCount,
                                  ReconciliationNeed reconciliation) {

        public SiegSideOutcome(XmlDownloadResultDTO result, boolean countFailed) {
            this(result, countFailed, null, null);
        }
    }

    /**
     * B1: a side's paging reached SIEG's last page with fewer notes delivered than SIEG counts, after a page that was not
     * the first one, and the difference ({@code totalSieg - delivered}) is bigger than the one the last complete pass of the
     * month left. The re-read goes from the first page, at most {@link #pages()} pages. {@code currentWindow}: the side's
     * regular window (its bookkeeping is refreshed at the end); false = the previous month checked in the first days.
     */
    public record ReconciliationNeed(UUID companyId, FiscalXmlSide side, LocalDate from, LocalDate to, int totalSieg,
                                     int delivered, boolean currentWindow) {

        public int pages() {
            return (totalSieg + PAGE_SIZE - 1) / PAGE_SIZE;
        }
    }

    /** One page of a reconciliation: the items SIEG returned, the new notes, and what SIEG has delivered of the window now. */
    public record ReconciledPage(int items, int persistedNew, int delivered) {
    }

    public FiscalXmlIntegrationService(SiegClient siegClient, FiscalXmlMetadataParser metadataParser,
                                       FiscalXmlFileStorage fileStorage, FiscalXmlPackageExtractor packageExtractor,
                                       FiscalDocumentRepository documentRepository, GoodsInvoiceRepository goodsInvoiceRepository,
                                       CaptureCountsService countsService, FiscalXmlSyncControlRepository syncControlRepository,
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
        this.documentRepository = documentRepository;
        this.goodsInvoiceRepository = goodsInvoiceRepository;
        this.countsService = countsService;
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
     * {@code SIEG_COUNT_FAILED} on the capture panel (CP-RN-10) -, the NFS-e count the same contar-xmls answered and the
     * reconciliation the side needs (run outside this transaction by the caller).
     *
     * <p>B1: the page asked for starts after what SIEG itself already delivered of this side and window
     * ({@code found_in_sieg_at}), read now from the database - never the notes another source (SEFAZ, upload) brought,
     * which are not SIEG's first positions. {@code filter.skip()} is not used here.
     */
    @Transactional
    public SiegSideOutcome syncSideFromSieg(XmlDownloadFilterDTO filter, boolean skipCountCheck) {
        Company company = findCompanyWithCnpj(filter.companyId());
        FiscalXmlSide side = filter.side() == null ? FiscalXmlSide.RECEIVED : filter.side();
        int deliveredBySieg = siegDelivered(company.getId(), side, filter.dataEmissaoInicio(), filter.dataEmissaoFim());
        XmlDownloadFilterDTO resumed = new XmlDownloadFilterDTO(filter.companyId(), filter.xmlType(), filter.dataEmissaoInicio(),
                filter.dataEmissaoFim(), filter.take(), deliveredBySieg, filter.cnpjDest(), side);
        return syncSideOutcome(company, resumed, side, skipCountCheck);
    }

    private XmlDownloadResultDTO syncSide(Company company, XmlDownloadFilterDTO filter, FiscalXmlSide side, boolean skipCountCheck) {
        // a manual sync never reconciles (it runs inside the request): the next scheduled attempt does
        return syncSideOutcome(company, filter, side, skipCountCheck).result();
    }

    /**
     * One side of the company: RECEIVED asks SIEG for notes where it's the recipient
     * ("CNPJdest"), ISSUED where it's the issuer ("CNPJemit").
     */
    private SiegSideOutcome syncSideOutcome(Company company, XmlDownloadFilterDTO filter, FiscalXmlSide side, boolean skipCountCheck) {
        String cnpj = DocumentNumbers.onlyDigits(company.getDocument().getNumber());
        SiegDownloadXmlsRequest request = new SiegDownloadXmlsRequest(
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
        CaptureCounts known = countsService.siegNfe(company);
        YearMonth month = filter.dataEmissaoInicio() == null ? null : YearMonth.from(filter.dataEmissaoInicio());
        int knownGap = known.knownGap(side.captureSide(), month);

        if (skipCountCheck) {
            log.info("Skipping contar-xmls for company {} ({}) - already known pending from a previous check", company.getId(), side);
            List<byte[]> xmlContents = siegClient.downloadXmls(request, apiKey);
            XmlDownloadResultDTO page = persistAll(company, xmlContents, DocumentSourceKey.SIEG);
            Integer knownTotal = month == null ? null
                    : known.row(side.captureSide(), month).map(CapturePeriodCount::getProviderTotal).orElse(null);
            PagingEnd end = knownTotal == null ? PagingEnd.NONE
                    : pagingEnd(company, side, filter, request.skip(), knownTotal, xmlContents.size(), knownGap, true);
            updateSyncControl(company, side, filter.dataEmissaoInicio(), filter.dataEmissaoFim(), null, end.gapToRecord());
            return new SiegSideOutcome(page, false, null, end.reconciliation());
        }

        SiegDocumentCounts counts = countDocuments(company, request, apiKey);
        int totalSieg = counts == null ? -1 : counts.nfe();

        XmlDownloadResultDTO result;
        PagingEnd end = PagingEnd.NONE;
        if (totalSieg == 0) {
            log.info("SIEG reports 0 pending NFe for company {} ({}) in this window, skipping baixar-xmls", company.getId(), side);
            result = new XmlDownloadResultDTO(0, 0, 0, 0, 0, List.of(), List.of(), 0);
        } else if (totalSieg > 0 && request.skip() != null && request.skip() + knownGap >= totalSieg) {
            // SIEG counts nothing beyond what it delivered (and the difference a complete pass already left): the page
            // after them would come back empty
            log.info("SIEG reports {} NFe for company {} ({}), {} delivered and {} known missing - skipping baixar-xmls",
                    totalSieg, company.getId(), side, request.skip(), knownGap);
            result = new XmlDownloadResultDTO(0, 0, 0, 0, 0, List.of(), List.of(), 0);
        } else {
            List<byte[]> xmlContents = siegClient.downloadXmls(request, apiKey);
            result = persistAll(company, xmlContents, DocumentSourceKey.SIEG);
            if (totalSieg > 0) {
                end = pagingEnd(company, side, filter, request.skip(), totalSieg, xmlContents.size(), knownGap, true);
            }
        }

        if (totalSieg >= 0) {
            updateSyncControl(company, side, filter.dataEmissaoInicio(), filter.dataEmissaoFim(), totalSieg, end.gapToRecord());
        }

        return new SiegSideOutcome(result, totalSieg < 0, counts == null ? null : counts.nfse(), end.reconciliation());
    }

    /**
     * How a side's paging ended this call. {@code gapToRecord}: a complete pass from the first page ended short - the
     * difference to keep for the month (null = nothing to record); {@code reconciliation}: the re-read the side needs.
     */
    record PagingEnd(Integer gapToRecord, ReconciliationNeed reconciliation) {
        static final PagingEnd NONE = new PagingEnd(null, null);
    }

    /**
     * B1: the page just stored ({@code pageItems} items) may be SIEG's last one while SIEG delivered fewer notes of the
     * window than its count - SIEG's order changed after the earlier pages (a note that arrived late in an earlier
     * position), so some notes sit before the skip. A page that was the first one already was a complete pass: its
     * difference is recorded. Otherwise a reconciliation from the first page is asked for - only when the difference is
     * bigger than the one the month's last complete pass left ({@code knownGap}): a difference SIEG never closes (notes it
     * counts and never delivers, a note counted on both sides, the month's edge in UTC) is not re-read every day.
     */
    private PagingEnd pagingEnd(Company company, FiscalXmlSide side, XmlDownloadFilterDTO filter, Integer skip, int totalSieg,
                                int pageItems, int knownGap, boolean currentWindow) {
        if (pageItems >= PAGE_SIZE) {
            return PagingEnd.NONE; // a full page: there is more, the next attempt goes on from there
        }
        int delivered = siegDelivered(company.getId(), side, filter.dataEmissaoInicio(), filter.dataEmissaoFim());
        int gap = totalSieg - delivered;
        if (gap <= 0) {
            return knownGap > 0 ? new PagingEnd(0, null) : PagingEnd.NONE;
        }
        if (skip == null || skip == 0) {
            return new PagingEnd(gap, null);
        }
        if (gap <= knownGap) {
            return PagingEnd.NONE;
        }
        return new PagingEnd(null, new ReconciliationNeed(company.getId(), side, filter.dataEmissaoInicio(), filter.dataEmissaoFim(),
                totalSieg, delivered, currentWindow));
    }

    /**
     * One page ({@code index} x 50, with events) of a reconciliation, stored in its own transaction - a failure on a later
     * page keeps what the earlier ones brought. Counts and keys only.
     */
    @Transactional
    public ReconciledPage reconcilePage(ReconciliationNeed need, int index) {
        Company company = findCompanyWithCnpj(need.companyId().toString());
        String cnpj = DocumentNumbers.onlyDigits(company.getDocument().getNumber());
        String apiKey = firmIntegrationService.getApiKey(company.getAccountingFirm().getId(), IntegrationType.SIEG);
        SiegDownloadXmlsRequest request = new SiegDownloadXmlsRequest(TIPO_XML_NFE, PAGE_SIZE, index * PAGE_SIZE,
                startOfDayUtc(need.from()), endOfDayUtc(need.to()),
                need.side() == FiscalXmlSide.ISSUED ? cnpj : null,
                need.side() == FiscalXmlSide.RECEIVED ? cnpj : null,
                true);
        List<byte[]> xmlContents = siegClient.downloadXmls(request, apiKey);
        XmlDownloadResultDTO result = persistAll(company, xmlContents, DocumentSourceKey.SIEG, false);
        return new ReconciledPage(xmlContents.size(), result.totalPersistedNew(),
                siegDelivered(company.getId(), need.side(), need.from(), need.to()));
    }

    /**
     * A reconciliation ran to its end: the side's "importado" is refreshed and the difference that is left is kept for
     * the month - the next one only when it grows.
     */
    @Transactional
    public void recordReconciliation(ReconciliationNeed need) {
        Company company = findCompanyWithCnpj(need.companyId().toString());
        int delivered = siegDelivered(company.getId(), need.side(), need.from(), need.to());
        CaptureCounts counts = countsService.siegNfe(company);
        CaptureSide side = need.side().captureSide();
        YearMonth month = YearMonth.from(need.from());
        counts.recordKnownGap(side, month, Math.max(0, need.totalSieg() - delivered), LocalDateTime.now(clock));
        if (!need.currentWindow()) {
            counts.refreshPreviousMonth(side, month, need.totalSieg(), delivered);
        } else {
            CapturePeriodCount row = counts.rowFor(side, month);
            row.correctImported(delivered);
            row.recordStoredAnySource(storedAnySource(company.getId(), need.side(), need.from(), need.to()));
        }
        countsService.save(counts);
    }

    /**
     * The previous month checked again in the first days of the month (an NF-e that only reaches SIEG after its month
     * ended is otherwise never asked for: the regular window is the current month). Same rules as the regular window: a
     * count from Skip 0; a download only when SIEG counts more than it delivered of that month plus the difference its
     * last complete pass left; the page starts after what SIEG delivered; a short end asks for a reconciliation (run by the
     * caller, outside this transaction) or records the difference. Never touches the current month's bookkeeping - only
     * the month's difference and, when it is the month the panel shows as closed, how it closed (CP-RN-22).
     */
    @Transactional
    public SiegSideOutcome checkPreviousMonthFromSieg(UUID companyId, FiscalXmlSide side, LocalDate from, LocalDate to) {
        Company company = findCompanyWithCnpj(companyId.toString());
        String cnpj = DocumentNumbers.onlyDigits(company.getDocument().getNumber());
        String apiKey = firmIntegrationService.getApiKey(company.getAccountingFirm().getId(), IntegrationType.SIEG);
        int delivered = siegDelivered(companyId, side, from, to);
        SiegDownloadXmlsRequest request = new SiegDownloadXmlsRequest(TIPO_XML_NFE, PAGE_SIZE, delivered, startOfDayUtc(from),
                endOfDayUtc(to), side == FiscalXmlSide.ISSUED ? cnpj : null, side == FiscalXmlSide.RECEIVED ? cnpj : null, true);
        SiegDocumentCounts counts = countDocuments(company, request, apiKey);
        XmlDownloadResultDTO none = new XmlDownloadResultDTO(0, 0, 0, 0, 0, List.of(), List.of(), 0);
        if (counts == null) {
            return new SiegSideOutcome(none, true, null, null);
        }
        YearMonth month = YearMonth.from(from);
        CaptureSide captureSide = side.captureSide();
        CaptureCounts control = countsService.siegNfe(company);
        int knownGap = control.knownGap(captureSide, month);
        if (counts.nfe() <= delivered + knownGap) {
            control.refreshPreviousMonth(captureSide, month, counts.nfe(), delivered);
            countsService.save(control);
            return new SiegSideOutcome(none, false, null, null);
        }
        log.info("SIEG counts {} NFe of company {} ({}) for {}, {} delivered and {} known missing - asking for the rest",
                counts.nfe(), companyId, side, month, delivered, knownGap);
        List<byte[]> xmlContents = siegClient.downloadXmls(request, apiKey);
        XmlDownloadResultDTO result = persistAll(company, xmlContents, DocumentSourceKey.SIEG, false);
        XmlDownloadFilterDTO window = new XmlDownloadFilterDTO(companyId.toString(), TIPO_XML_NFE, from, to, PAGE_SIZE, delivered,
                null, side);
        PagingEnd end = pagingEnd(company, side, window, delivered, counts.nfe(), xmlContents.size(), knownGap, false);
        if (end.gapToRecord() != null) {
            control.recordKnownGap(captureSide, month, end.gapToRecord(), LocalDateTime.now(clock));
        }
        control.refreshPreviousMonth(captureSide, month, counts.nfe(), siegDelivered(companyId, side, from, to));
        countsService.save(control);
        return new SiegSideOutcome(result, false, null, end.reconciliation());
    }

    /**
     * The side's notes of the window that SIEG itself delivered in its period mode (origin SIEG + PERIOD in
     * {@code fiscal_document_sources}) - SIEG's skip and the "importado" (B1, CA-FD-18).
     */
    private int siegDelivered(UUID companyId, FiscalXmlSide side, LocalDate from, LocalDate to) {
        return (int) goodsInvoiceRepository.countDeliveredBy(companyId, side.ownIssue(), IssuePeriod.siegStart(from),
                IssuePeriod.siegEndExclusive(to), DocumentSourceKey.SIEG, RetrievalMode.PERIOD);
    }

    /** The side's notes of the window in Taxify from any source - shown next to SIEG's numbers on the panel. */
    private int storedAnySource(UUID companyId, FiscalXmlSide side, LocalDate from, LocalDate to) {
        return (int) goodsInvoiceRepository.countStored(companyId, side.ownIssue(), IssuePeriod.siegStart(from),
                IssuePeriod.siegEndExclusive(to));
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

    /**
     * SIEG's count for the side's filter (NF-e and NFS-e); null = the count failed (RN-07: the download goes on). Always
     * from Skip 0 and without BaixarEventos: the whole window, notes only - comparable with what's stored.
     */
    private SiegDocumentCounts countDocuments(Company company, SiegDownloadXmlsRequest request, String apiKey) {
        try {
            SiegDownloadXmlsRequest countRequest = new SiegDownloadXmlsRequest(request.xmlType(), request.take(), 0,
                    request.issueDateFrom(), request.issueDateTo(), request.cnpjEmit(), request.cnpjDest(), null);
            return siegClient.countXmls(countRequest, apiKey);
        } catch (Exception e) {
            log.warn("Could not count pending SIEG NFe for company {} - proceeding with baixar-xmls anyway: {}", company.getId(),
                    e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * {@code totalSieg} is nullable: null means "not re-checked this call" (see the {@code skipCountCheck} path above) -
     * the existing value on the row is left as-is, while {@code totalImportado} and {@code ultimaVerificacaoEm} still get
     * refreshed to reflect whatever baixar-xmls just persisted. The "importado" is what SIEG delivered of the side's
     * window ({@code found_in_sieg_at}, B1): SIEG's total against SIEG's own notes - a note only SEFAZ or an upload
     * brought no longer hides one SIEG never delivered. The notes of the window from any source are kept next to it
     * for the panel.
     *
     * @param gapToRecord the difference a complete pass left for the window's month (null = unchanged) - replaces RN-08's
     *                    "total = imported": SIEG's total stays as SIEG said, and the scheduler counts the side as up to
     *                    date while nothing beyond that difference shows up
     */
    private void updateSyncControl(Company company, FiscalXmlSide side, LocalDate dataEmissaoInicio, LocalDate dataEmissaoFim,
                                   Integer totalSieg, Integer gapToRecord) {
        ensureControlRow(company);
        LocalDateTime now = LocalDateTime.now(clock);
        int imported = siegDelivered(company.getId(), side, dataEmissaoInicio, dataEmissaoFim);
        CaptureCounts counts = countsService.siegNfe(company);
        counts.closePreviousMonth(dataEmissaoInicio, now);
        // a manual sync without a window start (the scheduler always sends one) counts on the current month, windowless
        YearMonth month = dataEmissaoInicio == null ? YearMonth.from(now) : YearMonth.from(dataEmissaoInicio);
        CapturePeriodCount row = counts.rowFor(side.captureSide(), month);
        row.askedWindow(dataEmissaoInicio, dataEmissaoFim);
        if (gapToRecord != null) {
            counts.recordKnownGap(side.captureSide(), month, gapToRecord, now);
        }
        row.recordStoredAnySource(storedAnySource(company.getId(), side, dataEmissaoInicio, dataEmissaoFim));
        if (totalSieg != null) {
            row.recordProviderCount(totalSieg, now);
        }
        row.recordImported(imported, now);
        countsService.save(counts);
    }

    /** The company's row of the capture queue, created the first time it is synced (the scheduler creates the others). */
    private void ensureControlRow(Company company) {
        if (syncControlRepository.findByCompany_Id(company.getId()).isEmpty()) {
            FiscalXmlSyncControl created = new FiscalXmlSyncControl();
            created.setCompany(company);
            created.setDeleted(false);
            syncControlRepository.save(created);
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
                    total[0] = plus(total[0], persistAll(companyForBatch(id), batch, DocumentSourceKey.UPLOAD, false));
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
            total[0] = plus(total[0], persistAll(companyForBatch(id), batch, DocumentSourceKey.UPLOAD, false));
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
        return persistAll(findCompanyWithCnpj(companyId.toString()), xmlContents, DocumentSourceKey.SEFAZ_DFE);
    }

    /**
     * Stores what a provider page (or a manual upload) brought: SEFAZ events go to
     * FiscalXmlEventService, notes are persisted once per company - the same note may
     * legitimately belong to two clients of the firm (one's exit, the other's entry).
     * {@code source} is kept on a new note and marks, on new and known notes alike, that this
     * source has it (the SIEG x SEFAZ comparison); a SEFAZ summary of the note is completed.
     */
    private XmlDownloadResultDTO persistAll(Company company, List<byte[]> xmlContents, DocumentSourceKey source) {
        return persistAll(company, xmlContents, source, true);
    }

    /** @param withItems whether the answer lists each note with its XML (the SIEG search screen) or only counts and keys */
    private XmlDownloadResultDTO persistAll(Company company, List<byte[]> xmlContents, DocumentSourceKey source, boolean withItems) {
        int persistedNew = 0;
        int duplicated = 0;
        int parseErrors = 0;
        int events = 0;
        int notes = 0;
        List<String> persistedKeys = new ArrayList<>();
        List<FiscalXmlItemDTO> xmls = new ArrayList<>();

        int position = -1;
        for (byte[] xmlBytes : xmlContents) {
            position++;
            Optional<FiscalEventData> event = eventParser.parse(xmlBytes);
            if (event.isPresent()) {
                if (eventService.record(company, event.get())) {
                    events++;
                }
                continue;
            }
            notes++;

            // a service invoice (NFS-e) is an Nfse - it has no NF-e fields to fill
            if (NfseXml.looksLikeNfse(xmlBytes)) {
                switch (serviceInvoiceService.store(company, xmlBytes, LegacySourceNames.toLegacyName(source), position)) {
                    case NEW -> persistedNew++;
                    case DUPLICATE -> duplicated++;
                    case INVALID -> parseErrors++;
                }
                continue;
            }

            FiscalXmlMetadata metadata = metadataParser.parse(xmlBytes);
            String xmlContent = new String(xmlBytes, StandardCharsets.UTF_8);

            if (FiscalXmlMetadata.UNKNOWN.equals(metadata.documentType())) {
                // FD-RN-03: a file that is no NF-e, NFC-e nor CT-e has no subtype in the new model - a read failure,
                // not stored (the old table kept it as document_type UNKNOWN)
                parseErrors++;
                continue;
            }

            Optional<FiscalDocument> known = metadata.accessKey() == null ? Optional.empty()
                    : documentRepository.findFirstByCompany_IdAndAccessKey(company.getId(), metadata.accessKey());
            if (known.isPresent()) {
                duplicated++;
                // FD-RN-31: seen again - an atomic upsert, so a duplicate never fails the page (I-3)
                documentRepository.recordSeen(UUID.randomUUID().toString(), known.get().getId().toString(),
                        company.getId().toString(), source.value(), source.currentRetrievalMode().name(), LocalDateTime.now(clock));
                if (withItems) {
                    xmls.add(toItemDTO(metadata, xmlContent));
                }
                continue;
            }

            Optional<FiscalDocument> fresh = newDocument(metadata, company, source, xmlBytes, position);
            if (fresh.isEmpty()) {
                parseErrors++;
                continue;
            }
            FiscalDocument document = documentRepository.save(fresh.get());
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

    /**
     * The document of a new XML (FD-RN-03): built by {@link FiscalDocumentFactory} - what the model adjusted (decimals
     * rounded, party documents completed, I-17 and I-06) logged by {@link ImportLog} -, seen in {@code source}
     * (FD-RN-31), its invariants checked (CREATE), then the SEFAZ summary of the note completed and its file stored. A
     * document that breaks an invariant is not stored and touches nothing else (no summary completed, no file) - a read
     * failure of the batch, logged with the company, type, invariant, source, position in the batch and a short hash of
     * the key.
     */
    private Optional<FiscalDocument> newDocument(FiscalXmlMetadata metadata, Company company, DocumentSourceKey source,
                                                 byte[] xmlBytes, int position) {
        try {
            ImportAdjustments.Collected<Optional<FiscalDocument>> built = ImportAdjustments.collect(() ->
                    FiscalDocumentFactory.fromParsed(metadata, company, source, PENDING_FILE,
                            FiscalXmlMetadataParser.CURRENT_VERSION, NfeRetentions.parse(xmlBytes)));
            FiscalDocument document = built.value().orElse(null);
            if (document == null) {
                return Optional.empty();
            }
            ImportLog.adjustments(company, metadata.documentType(), source, position, built.adjustments());
            LocalDateTime now = LocalDateTime.now(clock);
            document.markSeenIn(source, source.currentRetrievalMode(), now);
            document.verifyInvariants(InvariantScope.CREATE);
            completeSummary(company, document);
            document.setXmlFilePath(fileStorage.store(company.getId(), metadata.accessKey(), xmlBytes));
            return Optional.of(document);
        } catch (FiscalDocumentInvariantException e) {
            ImportLog.violated(company, metadata.documentType(), e, source, position, metadata.accessKey());
            return Optional.empty();
        }
    }

    /** Placeholder path while the invariants are checked, before the file is stored (I-09 needs a path). */
    private static final String PENDING_FILE = "pending";

    /**
     * A SEFAZ summary of this note (an entry waiting for Ciência) is done now that the full XML is
     * here, whichever source brought it - and SEFAZ counts as having had the note since then.
     */
    private void completeSummary(Company company, FiscalDocument document) {
        if (document.getAccessKey() == null) {
            return;
        }
        summaryRepository.findByCompany_IdAndAccessKey(company.getId(), document.getAccessKey())
                .filter(summary -> !summary.isCompleted())
                .ifPresent(summary -> {
                    summary.setCompletedAt(LocalDateTime.now(clock));
                    summaryRepository.save(summary);
                    if (document.firstSeenIn(DocumentSourceKey.SEFAZ_DFE).isEmpty()) {
                        document.markSeenIn(DocumentSourceKey.SEFAZ_DFE, RetrievalMode.CURSOR,
                                summary.getCreatedDate() != null ? summary.getCreatedDate() : LocalDateTime.now(clock));
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
     * <p>A2: a side with no note in the database issued in [from, to] has nothing to be canceled - SIEG is not asked (a
     * local query, no request). A note not imported yet comes with its events in the regular download. The local query
     * starts one day earlier than SIEG's window: SIEG's days are UTC (00:00Z to 23:59Z), the stored issue time is the
     * note's local time - a note of 21:00 Recife on the day before {@code from} is already {@code from} in UTC.
     *
     * @return new events stored
     */
    @Transactional
    public int refreshRecentEvents(UUID companyId, FiscalXmlSide side, LocalDate from, LocalDate to) {
        Company company = findCompanyWithCnpj(companyId.toString());
        if (properties.isEventsOnlyWithRecentNotes() && !goodsInvoiceRepository.existsIssuedBetween(
                company.getId(), side.ownIssue(), IssuePeriod.start(from.minusDays(1)), IssuePeriod.endExclusive(to))) {
            log.info("Event sweep for company {} ({}): no note issued from {} to {} - no request spent", company.getId(), side, from, to);
            return 0;
        }
        String cnpj = DocumentNumbers.onlyDigits(company.getDocument().getNumber());
        String apiKey = firmIntegrationService.getApiKey(company.getAccountingFirm().getId(), IntegrationType.SIEG);
        int events = 0;
        for (int page = 0; page < EVENT_SWEEP_MAX_PAGES; page++) {
            SiegDownloadXmlsRequest request = new SiegDownloadXmlsRequest(TIPO_XML_NFE, PAGE_SIZE, page * PAGE_SIZE,
                    startOfDayUtc(from), endOfDayUtc(to),
                    side == FiscalXmlSide.ISSUED ? cnpj : null,
                    side == FiscalXmlSide.RECEIVED ? cnpj : null,
                    true);
            XmlDownloadResultDTO result = persistAll(company, siegClient.downloadXmls(request, apiKey), DocumentSourceKey.SIEG);
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
        SiegDownloadXmlsRequest request = new SiegDownloadXmlsRequest(TIPO_XML_NFE, PAGE_SIZE, page * PAGE_SIZE,
                startOfDayUtc(from), endOfDayUtc(to),
                side == FiscalXmlSide.ISSUED ? cnpj : null,
                side == FiscalXmlSide.RECEIVED ? cnpj : null,
                true);
        // counts and keys only - the recovery never answers with the XMLs themselves
        return persistAll(company, siegClient.downloadXmls(request, apiKey), DocumentSourceKey.SIEG, false);
    }

    public static int pageSize() {
        return PAGE_SIZE;
    }

    /**
     * Same company/period lookup as {@link FiscalXmlQueryService#search}, but serves each note's
     * rectified version ({@link GoodsInvoice#getRectifiedFilePath()}) instead of the
     * original whenever one exists - so an exported nota reflects the corrected CFOP/NCM
     * a {@link FiscalXmlRectificationService} run applied, not what SEFAZ originally
     * issued. A note never rectified simply falls back to {@code xmlFilePath}.
     */
    @Transactional(readOnly = true)
    public List<FiscalXmlItemDTO> exportXmls(XmlDownloadFilterDTO filter) {
        Company company = findCompanyWithCnpj(filter.companyId());

        LocalDateTime from = IssuePeriod.start(filter.dataEmissaoInicio());
        LocalDateTime to = IssuePeriod.endExclusive(filter.dataEmissaoFim());

        List<FiscalDocument> documents = documentRepository.findXmlDocumentsIssuedBetween(company.getId(), from, to);

        List<FiscalXmlItemDTO> xmls = new ArrayList<>();
        for (FiscalDocument document : documents) {
            String path = exportedPath(document);
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
        LocalDateTime to = IssuePeriod.endExclusive(filter.dataEmissaoFim());

        List<FiscalDocument> documents = documentRepository.findXmlDocumentsIssuedBetween(company.getId(), from, to);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Set<String> usedNames = new HashSet<>();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (FiscalDocument document : documents) {
                String path = exportedPath(document);
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

    /** The rectified file of a goods invoice when there is one, else the original. */
    private static String exportedPath(FiscalDocument document) {
        if (document instanceof GoodsInvoice goods && goods.getRectifiedFilePath() != null) {
            return goods.getRectifiedFilePath();
        }
        return document.getXmlFilePath();
    }

    private String uniqueEntryName(FiscalDocument document, Set<String> usedNames) {
        String base = document.accessKeyOrId();
        String name = base + ".xml";
        int suffix = 2;
        while (!usedNames.add(name)) {
            name = base + "-" + suffix++ + ".xml";
        }
        return name;
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
