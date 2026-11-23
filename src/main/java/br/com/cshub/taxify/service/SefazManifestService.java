package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.xml.SefazNoteSummary;
import br.com.cshub.taxify.domain.xml.SefazNoteSummary.CienciaStatus;
import br.com.cshub.taxify.integration.fiscalxml.FiscalEventData;
import br.com.cshub.taxify.integration.sefaz.A1Certificate;
import br.com.cshub.taxify.integration.sefaz.NoteSummaryXml;
import br.com.cshub.taxify.integration.sefaz.SefazDistClient;
import br.com.cshub.taxify.integration.sefaz.SefazDistResponse;
import br.com.cshub.taxify.integration.sefaz.SefazEventClient;
import br.com.cshub.taxify.integration.sefaz.SefazEventResponse;
import br.com.cshub.taxify.integration.sefaz.SefazProperties;
import br.com.cshub.taxify.repository.FiscalXmlDocumentRepository;
import br.com.cshub.taxify.repository.SefazNoteSummaryRepository;
import br.com.cshub.taxify.repository.SubscriptionRepository;
import br.com.cshub.taxify.domain.subscription.Subscription;
import br.com.cshub.taxify.enumerated.PlanServiceEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The recipient's side of the SEFAZ distribution: summaries (resNFe) are kept as notes waiting
 * for their XML, the Ciência da Operação is sent for them (signed with the company's A1) and,
 * on a later run, each one is asked by key until the full procNFe comes back and is imported -
 * which completes the summary. A note that arrives by SIEG or upload first completes it too.
 * The ciência only goes for companies whose plan has the manifestation service
 * ({@link PlanServiceEnum#SEFAZ_MANIFESTATION}); the summaries are kept for every company.
 */
@Service
public class SefazManifestService {

    private static final Logger log = LoggerFactory.getLogger(SefazManifestService.class);
    /** Ask by key only after the ciência had time to be processed - on a later run. */
    private static final long FETCH_AFTER_MINUTES = 30;
    /** A note asked by key and not there yet isn't asked again before this. */
    private static final long FETCH_AGAIN_HOURS = 6;
    /** Notes SEFAZ no longer serves by key (older than 90 days) or won't give this CNPJ. */
    private static final java.util.Set<String> FETCH_GIVE_UP = java.util.Set.of("632", "640", "641", "653");

    private final SefazNoteSummaryRepository summaryRepository;
    private final FiscalXmlDocumentRepository documentRepository;
    private final FiscalXmlEventService eventService;
    private final FiscalXmlIntegrationService integrationService;
    private final SefazEventClient eventClient;
    private final SefazDistClient distClient;
    private final SefazProperties properties;
    private final SubscriptionRepository subscriptionRepository;

    public SefazManifestService(SefazNoteSummaryRepository summaryRepository, FiscalXmlDocumentRepository documentRepository,
                                FiscalXmlEventService eventService, FiscalXmlIntegrationService integrationService,
                                SefazEventClient eventClient, SefazDistClient distClient, SefazProperties properties,
                                SubscriptionRepository subscriptionRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.summaryRepository = summaryRepository;
        this.documentRepository = documentRepository;
        this.eventService = eventService;
        this.integrationService = integrationService;
        this.eventClient = eventClient;
        this.distClient = distClient;
        this.properties = properties;
    }

    /** What the ciência / fetch step did in one run, for the control row. */
    public record Outcome(int cienciasSent, int cienciaErrors, int fetched, boolean blocked, String error) {
        static final Outcome NONE = new Outcome(0, 0, 0, false, null);
    }

    /**
     * Keeps the summaries of one distribution batch. A note already stored (SIEG got it first)
     * only gets SEFAZ marked as having it; a known summary takes the newer situation (a cancellation).
     *
     * @return summaries new for the company
     */
    public int recordSummaries(Company company, List<SefazDistResponse.Document> documents) {
        int created = 0;
        for (SefazDistResponse.Document document : documents) {
            if (!document.isNoteSummary()) {
                continue;
            }
            NoteSummaryXml summary = NoteSummaryXml.parse(document.xml()).orElse(null);
            if (summary == null) {
                continue;
            }
            var stored = documentRepository.findFirstByCompany_IdAndAccessKey(company.getId(), summary.accessKey());
            if (stored.isPresent()) {
                if (stored.get().getFoundInSefazAt() == null) {
                    stored.get().setFoundInSefazAt(LocalDateTime.now());
                    documentRepository.save(stored.get());
                }
                continue;
            }
            SefazNoteSummary entity = summaryRepository.findByCompany_IdAndAccessKey(company.getId(), summary.accessKey())
                    .orElse(null);
            if (entity == null) {
                entity = new SefazNoteSummary();
                entity.setCompany(company);
                entity.setAccessKey(summary.accessKey());
                entity.setDeleted(false);
                created++;
            }
            entity.setNsu(document.nsu());
            entity.setIssuerDocument(summary.issuerDocument());
            entity.setIssuerName(summary.issuerName());
            entity.setIssuerIe(summary.issuerIe());
            entity.setIssueDate(summary.issueDate());
            entity.setOperationType(summary.operationType());
            entity.setTotalValue(summary.totalValue());
            entity.setSituation(summary.situation());
            entity.setAuthorizedAt(summary.authorizedAt());
            entity.setProtocol(summary.protocol());
            summaryRepository.save(entity);
        }
        return created;
    }

    /**
     * Sends the ciência for the summaries waiting for one and then asks, by key, for the notes whose
     * ciência went through on an earlier run. Stops at the first SEFAZ refusal of the connection or
     * a 656 (the hour's pause).
     *
     * @param mayFetch false when this run already got a 656 from the distribution
     */
    public Outcome manifestAndFetch(Company company, A1Certificate certificate, String cnpj, boolean mayFetch) {
        if (!manifests(company.getId())) {
            return Outcome.NONE;
        }
        int sent = 0;
        int errors = 0;
        int fetched = 0;
        boolean blocked = false;
        String error = null;
        try {
            List<SefazNoteSummary> waiting = summaryRepository.findAwaitingCiencia(company.getId(),
                    properties.getCienciaMaxAttempts(), PageRequest.of(0, Math.max(1, properties.getCienciaPerRun())));
            for (int from = 0; from < waiting.size(); from += SefazEventClient.MAX_PER_BATCH) {
                List<SefazNoteSummary> batch = waiting.subList(from, Math.min(waiting.size(), from + SefazEventClient.MAX_PER_BATCH));
                int[] counts = sendBatch(company, certificate, cnpj, batch);
                sent += counts[0];
                errors += counts[1];
            }
            if (mayFetch) {
                LocalDateTime now = LocalDateTime.now();
                List<SefazNoteSummary> released = summaryRepository.findAwaitingFullXml(company.getId(),
                        now.minusMinutes(FETCH_AFTER_MINUTES), now.minusHours(FETCH_AGAIN_HOURS),
                        PageRequest.of(0, Math.max(1, properties.getFetchPerRun())));
                for (SefazNoteSummary summary : released) {
                    FetchResult result = fetch(company, certificate, cnpj, summary);
                    if (result == FetchResult.IMPORTED) {
                        fetched++;
                    } else if (result == FetchResult.BLOCKED) {
                        blocked = true;
                        break;
                    }
                }
            }
        } catch (IOException e) {
            log.warn("SEFAZ ciência / fetch failed for company {}", company.getId(), e);
            error = "Falha de conexão com a SEFAZ na ciência: " + e.getMessage();
        } catch (GeneralSecurityException e) {
            error = "Certificado recusado na ciência: " + e.getMessage();
        }
        return new Outcome(sent, errors, fetched, blocked, error);
    }

    /** The server allows it (sefaz.dist.auto-ciencia) and today's plan has the manifestation service. */
    public boolean manifests(java.util.UUID companyId) {
        if (!properties.isAutoCiencia()) {
            return false;
        }
        java.time.LocalDate today = java.time.LocalDate.now();
        return subscriptionRepository
                .findFirstByCompanyIdAndDeletedIsFalseAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateDesc(
                        companyId, today, today)
                .map(Subscription::getPlan)
                .map(plan -> PlanServiceEnum.manifests(plan.getServices()))
                .orElse(false);
    }

    /** @return {registered or already done, refused} */
    private int[] sendBatch(Company company, A1Certificate certificate, String cnpj, List<SefazNoteSummary> batch)
            throws IOException, GeneralSecurityException {
        SefazEventResponse response = eventClient.sendCiencia(certificate, cnpj,
                batch.stream().map(SefazNoteSummary::getAccessKey).toList());
        LocalDateTime now = LocalDateTime.now();
        if (!response.processed()) {
            String detail = truncate("Lote recusado: " + response.cStat() + " " + response.xMotivo());
            for (SefazNoteSummary summary : batch) {
                refused(summary, detail);
            }
            summaryRepository.saveAll(batch);
            return new int[]{0, batch.size()};
        }
        Map<String, SefazEventResponse.Result> byKey = response.results().stream()
                .filter(result -> result.accessKey() != null)
                .collect(Collectors.toMap(SefazEventResponse.Result::accessKey, Function.identity(), (a, b) -> a));
        int ok = 0;
        int refused = 0;
        for (SefazNoteSummary summary : batch) {
            SefazEventResponse.Result result = byKey.get(summary.getAccessKey());
            if (result != null && (result.registered() || result.alreadyDone())) {
                summary.setCienciaStatus(CienciaStatus.ENVIADA);
                summary.setCienciaAt(now);
                summary.setCienciaProtocol(result.protocol());
                summary.setCienciaDetail(result.alreadyDone() ? "A nota já tinha ciência registrada." : null);
                ok++;
                if (result.registered()) {
                    eventService.record(company, new FiscalEventData(summary.getAccessKey(), SefazEventClient.CIENCIA,
                            "Ciência da Operação", 1, registeredAt(result.registeredAt(), now), result.protocol(),
                            "Enviada pelo Taxify", true));
                }
            } else {
                refused(summary, truncate(result == null ? "Sem retorno da SEFAZ para a nota."
                        : result.cStat() + " " + result.xMotivo()));
                refused++;
            }
        }
        summaryRepository.saveAll(batch);
        return new int[]{ok, refused};
    }

    private static void refused(SefazNoteSummary summary, String detail) {
        summary.setCienciaStatus(CienciaStatus.ERRO);
        summary.setCienciaAttempts(summary.getCienciaAttempts() + 1);
        summary.setCienciaDetail(detail);
    }

    private enum FetchResult { IMPORTED, NOT_YET, BLOCKED }

    private FetchResult fetch(Company company, A1Certificate certificate, String cnpj, SefazNoteSummary summary)
            throws IOException, GeneralSecurityException {
        SefazDistResponse response = distClient.fetchByKey(certificate, company.getUf(), cnpj, summary.getAccessKey());
        summary.setLastFetchAt(LocalDateTime.now());
        if ("656".equals(response.cStat())) {
            summaryRepository.save(summary);
            return FetchResult.BLOCKED;
        }
        List<byte[]> full = response.documents().stream()
                .filter(SefazDistResponse.Document::isFullNote)
                .map(SefazDistResponse.Document::xml)
                .toList();
        if (!full.isEmpty()) {
            summary.setFetchDetail(null);
            summaryRepository.save(summary);
            // importing completes the summary (FiscalXmlIntegrationService#completeSummary)
            integrationService.importFromSefaz(company.getId(), full);
            return FetchResult.IMPORTED;
        }
        summary.setFetchDetail(truncate(response.cStat() + " " + response.xMotivo()));
        if (FETCH_GIVE_UP.contains(response.cStat())) {
            // SEFAZ won't serve it by key: the next ciência round doesn't change that - wait for SIEG or an upload
            summary.setCienciaStatus(CienciaStatus.ERRO);
            summary.setCienciaAttempts(properties.getCienciaMaxAttempts());
        }
        summaryRepository.save(summary);
        return FetchResult.NOT_YET;
    }

    private static LocalDateTime registeredAt(String value, LocalDateTime fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return OffsetDateTime.parse(value.strip()).toLocalDateTime();
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 255 ? value : value.substring(0, 255);
    }
}
