package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.DocumentNumbers;
import br.com.cshub.taxify.common.IssuePeriod;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.fiscaldocument.DocumentSourceKey;
import br.com.cshub.taxify.domain.fiscaldocument.Nfse;
import br.com.cshub.taxify.enumerated.RetrievalMode;
import br.com.cshub.taxify.exception.FiscalDocumentInvariantException;
import br.com.cshub.taxify.enumerated.InvariantScope;
import br.com.cshub.taxify.mapper.fiscaldocument.FiscalDocumentFactory;
import br.com.cshub.taxify.mapper.fiscaldocument.LegacyNfseDirection;
import br.com.cshub.taxify.mapper.fiscaldocument.LegacySourceNames;
import br.com.cshub.taxify.enumerated.IntegrationType;
import br.com.cshub.taxify.integration.nfse.NfseXml;
import br.com.cshub.taxify.integration.sieg.FiscalXmlFileStorage;
import br.com.cshub.taxify.integration.sieg.SiegDownloadXmlsRequest;
import br.com.cshub.taxify.integration.sieg.SiegClient;
import br.com.cshub.taxify.repository.NfseRepository;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Brings in and stores the companies' service invoices (NFS-e): from SIEG (TipoXml 3), provided
 * and taken, and from manual uploads. One row per note and company - a note found again is
 * skipped, unless it now comes canceled. The direction comes from the company's CNPJ: provider
 * = PRESTADA, taker = TOMADA.
 */
@Service
public class ServiceInvoiceService {

    private static final Logger log = LoggerFactory.getLogger(ServiceInvoiceService.class);
    /** SIEG's document type for NFS-e ("1: NF-e / 2: CT-e / 3: NFS-e / 4: NFC-e / 5: CF-e"). */
    static final int TIPO_XML_NFSE = 3;
    private static final int PAGE_SIZE = 50;
    /** Bounds one side of one window - 1000 notes; a bigger month is completed by the next days' runs. */
    private static final int MAX_PAGES = 20;

    public enum Stored { NEW, DUPLICATE, INVALID }

    private final NfseRepository repository;
    private final FiscalXmlFileStorage fileStorage;
    private final SiegClient siegClient;
    private final FirmIntegrationService firmIntegrationService;
    private final CompanyValidation companyValidation;

    public ServiceInvoiceService(NfseRepository repository, FiscalXmlFileStorage fileStorage, SiegClient siegClient,
                                 FirmIntegrationService firmIntegrationService, CompanyValidation companyValidation) {
        this.repository = repository;
        this.fileStorage = fileStorage;
        this.siegClient = siegClient;
        this.firmIntegrationService = firmIntegrationService;
        this.companyValidation = companyValidation;
    }

    /** Result of one window: notes new, already known, unreadable. */
    public record SyncResult(int persistedNew, int duplicated, int invalid) {
        SyncResult plus(Stored stored) {
            return new SyncResult(persistedNew + (stored == Stored.NEW ? 1 : 0), duplicated + (stored == Stored.DUPLICATE ? 1 : 0),
                    invalid + (stored == Stored.INVALID ? 1 : 0));
        }

        SyncResult plus(SyncResult other) {
            return new SyncResult(persistedNew + other.persistedNew, duplicated + other.duplicated, invalid + other.invalid);
        }
    }

    /**
     * The company's NFS-e issued in [from, to] from SIEG: as provider (CnpjEmit) and as taker (CnpjDest).
     * SIEG doesn't document which filter is which for NFS-e - both are asked and the direction is
     * decided by the note itself, so a swapped mapping still lands right. SIEG refuses a range over
     * 2 months, so the period goes in windows of at most one month.
     */
    @Transactional
    public SyncResult syncFromSieg(UUID companyId, LocalDate from, LocalDate to) {
        Company company = companyValidation.checkExist(companyId);
        String cnpj = DocumentNumbers.onlyDigits(company.getDocument().getNumber());
        String apiKey = firmIntegrationService.getApiKey(company.getAccountingFirm().getId(), IntegrationType.SIEG);
        SyncResult result = new SyncResult(0, 0, 0);
        for (LocalDate[] window : windows(from, to)) {
            result = result.plus(downloadWindow(company, cnpj, apiKey, window[0], window[1]));
        }
        log.info("NFS-e from SIEG for company {} ({} to {}): {} new, {} known, {} unreadable", companyId, from, to,
                result.persistedNew(), result.duplicated(), result.invalid());
        return result;
    }

    /**
     * A1: the company's NFS-e of one window [from, to] (at most a month), downloaded only when SIEG counts more than it
     * already delivered of that window ({@code found_in_sieg_at}, any direction - a known note is never downloaded again
     * for nothing). SIEG's total is the sum of its NFS-e counts with the company as issuer (CnpjEmit) and as recipient
     * (CnpjDest): {@code issuerCount}/{@code recipientCount} when the NF-e sides already counted this window in this
     * attempt (contar-xmls answers every type), else one contar-xmls for each one missing. SIEG does not document which
     * filter is the provider and which the taker for NFS-e, so only the total is compared, never a direction. A count that
     * fails means "unknown": the window is downloaded, as before.
     */
    @Transactional
    public SyncResult syncMonthIfMissing(UUID companyId, LocalDate from, LocalDate to, Integer issuerCount, Integer recipientCount) {
        Company company = companyValidation.checkExist(companyId);
        String cnpj = DocumentNumbers.onlyDigits(company.getDocument().getNumber());
        String apiKey = firmIntegrationService.getApiKey(company.getAccountingFirm().getId(), IntegrationType.SIEG);
        Integer asIssuer = issuerCount != null ? issuerCount : countNfse(company, apiKey, cnpj, null, from, to);
        Integer asRecipient = recipientCount != null ? recipientCount : countNfse(company, apiKey, null, cnpj, from, to);
        long delivered = siegDelivered(companyId, from, to);
        if (asIssuer != null && asRecipient != null && delivered >= (long) asIssuer + asRecipient) {
            log.info("NFS-e of company {} ({} to {}): SIEG counts {}, {} already delivered - no download", companyId, from, to,
                    asIssuer + asRecipient, delivered);
            return new SyncResult(0, 0, 0);
        }
        SyncResult result = downloadWindow(company, cnpj, apiKey, from, to);
        log.info("NFS-e of company {} ({} to {}): SIEG counts {}, {} delivered before - {} new, {} known, {} unreadable", companyId,
                from, to, asIssuer == null || asRecipient == null ? "?" : String.valueOf(asIssuer + asRecipient), delivered,
                result.persistedNew(), result.duplicated(), result.invalid());
        return result;
    }

    /**
     * A1 in shadow (decision of 2026-11-27): [from, to] is downloaded exactly as without the gate, and the current month
     * ([first day of {@code to}'s month, to]) is checked with the counts this attempt already has - free, no request of its
     * own: one log line per company with ids and counts, saying what the gate would have done. A line "would have MISSED"
     * means the gate would have skipped a month that brought new notes - the gate is not safe to turn on. Only the current
     * month is evaluated: the earlier months of the lookback that the ON mode checks (with counts of their own) never show
     * up in this log - counting them here would cost 2 requests a month on top of the old download.
     */
    @Transactional
    public SyncResult syncWithShadowGate(UUID companyId, LocalDate from, LocalDate to, Integer issuerCount, Integer recipientCount) {
        LocalDate monthStart = to.withDayOfMonth(1);
        boolean counted = issuerCount != null && recipientCount != null;
        long before = counted ? siegDelivered(companyId, monthStart, to) : 0;
        SyncResult result = syncFromSieg(companyId, from, to);
        if (!counted) {
            log.info("NFS-e shadow gate company {} ({} to {}): no count from this attempt - not evaluated", companyId, monthStart, to);
            return result;
        }
        long after = siegDelivered(companyId, monthStart, to);
        int siegTotal = issuerCount + recipientCount;
        boolean wouldSkip = before >= siegTotal;
        if (wouldSkip && after > before) {
            log.warn("NFS-e shadow gate company {} ({} to {}): SIEG counts {} (issuer {} + recipient {}), {} delivered before, {} new"
                    + " in the month - the gate would have MISSED them", companyId, monthStart, to, siegTotal, issuerCount,
                    recipientCount, before, after - before);
        } else {
            log.info("NFS-e shadow gate company {} ({} to {}): SIEG counts {} (issuer {} + recipient {}), {} delivered before, {} new"
                    + " in the month - the gate would have {}", companyId, monthStart, to, siegTotal, issuerCount, recipientCount,
                    before, after - before, wouldSkip ? "skipped the month" : "downloaded the month");
        }
        return result;
    }

    private long siegDelivered(UUID companyId, LocalDate from, LocalDate to) {
        return repository.countDeliveredBy(companyId, IssuePeriod.siegStart(from), IssuePeriod.siegEnd(to),
                DocumentSourceKey.SIEG, RetrievalMode.PERIOD);
    }

    /** SIEG's NFS-e count for one filter of the window; null = the count failed. */
    private Integer countNfse(Company company, String apiKey, String cnpjEmit, String cnpjDest, LocalDate from, LocalDate to) {
        try {
            SiegDownloadXmlsRequest request = new SiegDownloadXmlsRequest(TIPO_XML_NFSE, PAGE_SIZE, 0, startOfDay(from), endOfDay(to),
                    cnpjEmit, cnpjDest, null);
            return siegClient.countXmls(request, apiKey).nfse();
        } catch (RuntimeException e) {
            log.warn("Could not count SIEG NFS-e for company {} - downloading the window: {}", company.getId(),
                    e.getClass().getSimpleName());
            return null;
        }
    }

    /** Both filters (as provider - CnpjEmit - and as taker - CnpjDest) of one window, paged until a short page. */
    private SyncResult downloadWindow(Company company, String cnpj, String apiKey, LocalDate from, LocalDate to) {
        SyncResult result = new SyncResult(0, 0, 0);
        for (boolean asProvider : new boolean[]{true, false}) {
            for (int page = 0; page < MAX_PAGES; page++) {
                SiegDownloadXmlsRequest request = new SiegDownloadXmlsRequest(TIPO_XML_NFSE, PAGE_SIZE, page * PAGE_SIZE,
                        startOfDay(from), endOfDay(to), asProvider ? cnpj : null, asProvider ? null : cnpj, null);
                List<byte[]> xmls = siegClient.downloadXmls(request, apiKey);
                for (byte[] xml : xmls) {
                    result = result.plus(store(company, xml, "SIEG"));
                }
                if (xmls.size() < PAGE_SIZE) {
                    break;
                }
            }
        }
        return result;
    }

    /** [from, to] split into consecutive windows of at most one month (SIEG accepts up to 2). */
    static List<LocalDate[]> windows(LocalDate from, LocalDate to) {
        List<LocalDate[]> windows = new java.util.ArrayList<>();
        for (LocalDate start = from; !start.isAfter(to); ) {
            LocalDate end = start.plusMonths(1).minusDays(1);
            if (end.isAfter(to)) {
                end = to;
            }
            windows.add(new LocalDate[]{start, end});
            start = end.plusDays(1);
        }
        return windows;
    }

    /**
     * Stores one NFS-e XML of the company (any layout); a known note only takes a cancellation and the origin.
     * {@code source} is the name the callers use today ({@code SIEG}, {@code ADN}, {@code UPLOAD}, {@code SEFAZ_DIST}),
     * translated to the source of the model ({@link LegacySourceNames}). A note that breaks an invariant of the new model
     * (FD-RN-03) is not stored: INVALID, as an unreadable one.
     */
    @Transactional
    public Stored store(Company company, byte[] xml, String source) {
        Optional<NfseXml> parsed = NfseXml.parse(xml);
        if (parsed.isEmpty()) {
            return Stored.INVALID;
        }
        NfseXml nfse = parsed.get();
        DocumentSourceKey origin = LegacySourceNames.toSource(source);
        Optional<Nfse> known = repository.findByCompany_IdAndAccessKey(company.getId(), nfse.accessKey());
        if (known.isPresent()) {
            markSeenIn(known.get(), origin);
            if (nfse.canceled()) {
                known.get().cancelByLayout();
            }
            repository.save(known.get());
            return Stored.DUPLICATE;
        }
        Nfse invoice;
        try {
            invoice = FiscalDocumentFactory.fromParsed(nfse, company, origin, "NFSE-" + nfse.accessKey());
            markSeenIn(invoice, origin);
            invoice.verifyInvariants(InvariantScope.CREATE);
        } catch (FiscalDocumentInvariantException | IllegalArgumentException e) {
            log.warn("fiscal.document.invariant.violated id={} type=NFSE invariant={} source={}", null,
                    e instanceof FiscalDocumentInvariantException violated ? violated.getInvariant() : e.getClass().getSimpleName(),
                    source);
            return Stored.INVALID;
        }
        invoice.setXmlFilePath(fileStorage.store(company.getId(), "NFSE-" + nfse.accessKey(), xml));
        repository.save(invoice);
        return Stored.NEW;
    }

    /** A cancellation event (Ambiente Nacional): marks the company's note canceled. @return true when it changed */
    @Transactional
    public boolean markCanceled(Company company, String accessKey) {
        return repository.findByCompany_IdAndAccessKey(company.getId(), accessKey)
                .filter(invoice -> !invoice.isCanceled())
                .map(invoice -> {
                    invoice.cancelByLayout();
                    repository.save(invoice);
                    return true;
                })
                .orElse(false);
    }

    /** FD-RN-31: the source delivered the note now - first or last time. */
    private void markSeenIn(Nfse invoice, DocumentSourceKey source) {
        invoice.markSeenIn(source, source.currentRetrievalMode(), LocalDateTime.now());
    }

    /** The company is the provider (PRESTADA), the taker (TOMADA) or neither (TERCEIRO). */
    static String directionOf(Company company, NfseXml nfse) {
        return FiscalDocumentFactory.nfseDirection(company, nfse).name();
    }

    private static Instant startOfDay(LocalDate date) {
        return date == null ? null : date.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static Instant endOfDay(LocalDate date) {
        return date == null ? null : date.atTime(LocalTime.MAX).atZone(ZoneOffset.UTC).toInstant();
    }
}
