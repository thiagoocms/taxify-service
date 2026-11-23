package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.RequestIds;
import br.com.cshub.taxify.domain.certificate.CompanyCertificate;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
import br.com.cshub.taxify.domain.xml.FiscalXmlEvent;
import br.com.cshub.taxify.domain.xml.SefazNoteSummary;
import br.com.cshub.taxify.domain.xml.SefazNoteSummary.CienciaStatus;
import br.com.cshub.taxify.dto.fiscalxml.ManifestationDTOs;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.integration.fiscalxml.FiscalEventData;
import br.com.cshub.taxify.integration.sefaz.A1Certificate;
import br.com.cshub.taxify.integration.sefaz.SefazEventClient;
import br.com.cshub.taxify.integration.sefaz.SefazEventClient.EventRequest;
import br.com.cshub.taxify.integration.sefaz.SefazEventClient.Manifestation;
import br.com.cshub.taxify.integration.sefaz.SefazEventResponse;
import br.com.cshub.taxify.repository.FiscalXmlDocumentRepository;
import br.com.cshub.taxify.repository.FiscalXmlEventRepository;
import br.com.cshub.taxify.repository.SefazNoteSummaryRepository;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The recipient's manifestation by hand: lists the entries that still owe a conclusive answer
 * (confirmation, unknown or not performed - 180 days after authorization) and sends the chosen
 * event for several notes at once, signed with the company's A1. Each registered event goes to the
 * note's events like any other; a summary that gets one is released and fetched by key afterwards.
 */
@Service
public class SefazManifestationScreenService {

    private static final Logger log = LoggerFactory.getLogger(SefazManifestationScreenService.class);
    /** The recipient has 180 days from the authorization to give a conclusive manifestation. */
    static final int DEADLINE_DAYS = 180;
    private static final int MAX_LISTED = 500;
    private static final int MAX_PER_REQUEST = 100;
    private static final Set<String> CONCLUSIVE = Arrays.stream(Manifestation.values())
            .filter(type -> type.conclusive).map(type -> type.code).collect(Collectors.toUnmodifiableSet());

    private final FiscalXmlDocumentRepository documentRepository;
    private final SefazNoteSummaryRepository summaryRepository;
    private final FiscalXmlEventRepository eventRepository;
    private final FiscalXmlEventService eventService;
    private final CompanyCertificateService certificateService;
    private final SefazManifestService manifestService;
    private final SefazEventClient eventClient;
    private final CompanyValidation companyValidation;
    private final MessageService messageService;

    public SefazManifestationScreenService(FiscalXmlDocumentRepository documentRepository,
                                           SefazNoteSummaryRepository summaryRepository,
                                           FiscalXmlEventRepository eventRepository, FiscalXmlEventService eventService,
                                           CompanyCertificateService certificateService, SefazManifestService manifestService,
                                           SefazEventClient eventClient, CompanyValidation companyValidation,
                                           MessageService messageService) {
        this.documentRepository = documentRepository;
        this.summaryRepository = summaryRepository;
        this.eventRepository = eventRepository;
        this.eventService = eventService;
        this.certificateService = certificateService;
        this.manifestService = manifestService;
        this.eventClient = eventClient;
        this.companyValidation = companyValidation;
        this.messageService = messageService;
    }

    @Transactional(readOnly = true)
    public ManifestationDTOs.Pending pending(String companyId) {
        Company company = companyValidation.checkExist(RequestIds.parse(companyId, messageService));
        return new ManifestationDTOs.Pending(manifestService.manifests(company.getId()), validCertificate(company).isPresent(),
                notesOf(company));
    }

    /** The company's entries without a conclusive manifestation, nearest deadline first (also the pendencies' source). */
    @Transactional(readOnly = true)
    public List<ManifestationDTOs.Note> notesOf(Company company) {
        String cnpj = CompanyCertificateService.cnpjOf(company);
        if (cnpj == null) {
            return List.of();
        }
        LocalDateTime since = LocalDate.now().minusDays(DEADLINE_DAYS).atStartOfDay();
        PageRequest page = PageRequest.of(0, MAX_LISTED);
        Map<String, ManifestationDTOs.Note> byKey = new LinkedHashMap<>();
        List<FiscalXmlDocument> documents = documentRepository.findAwaitingManifestation(company.getId(), cnpj, since, CONCLUSIVE, page);
        List<SefazNoteSummary> summaries = summaryRepository.findAwaitingManifestation(company.getId(), since, CONCLUSIVE, page);

        Set<String> keys = new HashSet<>();
        documents.forEach(document -> keys.add(document.getAccessKey()));
        summaries.forEach(summary -> keys.add(summary.getAccessKey()));
        Set<String> withCiencia = keys.isEmpty() ? Set.of() : eventRepository
                .findByCompany_IdAndAccessKeyInAndRegisteredIsTrue(company.getId(), keys).stream()
                .filter(event -> Manifestation.CIENCIA.code.equals(event.getEventType()))
                .map(FiscalXmlEvent::getAccessKey)
                .collect(Collectors.toSet());

        LocalDate today = LocalDate.now();
        for (FiscalXmlDocument document : documents) {
            byKey.put(document.getAccessKey(), note(document.getAccessKey(), document.getId(), document.getNumero(),
                    document.getSerie(), document.getEmitenteNome(),
                    document.getIssuerCnpj() != null ? document.getIssuerCnpj() : document.getEmitenteCpf(),
                    document.getIssueDate(), document.getValorTotalNota(), true,
                    withCiencia.contains(document.getAccessKey()), document.getIssueDate(), today));
        }
        for (SefazNoteSummary summary : summaries) {
            byKey.putIfAbsent(summary.getAccessKey(), note(summary.getAccessKey(), null, numberOf(summary.getAccessKey()),
                    seriesOf(summary.getAccessKey()), summary.getIssuerName(), summary.getIssuerDocument(),
                    summary.getIssueDate(), summary.getTotalValue(), false,
                    withCiencia.contains(summary.getAccessKey()) || summary.getCienciaStatus() == CienciaStatus.ENVIADA,
                    summary.getAuthorizedAt() != null ? summary.getAuthorizedAt() : summary.getIssueDate(), today));
        }
        List<ManifestationDTOs.Note> notes = new ArrayList<>(byKey.values());
        notes.sort(Comparator.comparing(ManifestationDTOs.Note::prazo, Comparator.nullsLast(Comparator.naturalOrder())));
        return notes;
    }

    /** Sends the chosen manifestation for the notes - in batches of 20 - and tells what SEFAZ said for each. */
    public ManifestationDTOs.Result send(ManifestationDTOs.Request request) {
        Company company = companyValidation.checkExist(RequestIds.parse(request.companyId(), messageService));
        Manifestation type;
        try {
            type = Manifestation.valueOf(request.tipo().strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(messageService.getMessage("error.generic.bad.request"));
        }
        List<String> keys = request.chaves().stream()
                .filter(key -> key != null && !key.isBlank())
                .map(key -> key.replaceAll("\\D", ""))
                .distinct()
                .toList();
        if (keys.isEmpty() || keys.size() > MAX_PER_REQUEST || keys.stream().anyMatch(key -> key.length() != 44)) {
            throw new BadRequestException(messageService.getMessage("error.sefaz.manifestation.notes"));
        }
        String justification = request.justificativa() == null ? null : request.justificativa().replaceAll("\\s+", " ").strip();
        if (type.needsJustification() && (justification == null || justification.length() < 15 || justification.length() > 255)) {
            throw new BadRequestException(messageService.getMessage("error.sefaz.manifestation.justification"));
        }
        if (!manifestService.manifests(company.getId())) {
            throw new BadRequestException(messageService.getMessage("error.sefaz.manifestation.not.in.plan"));
        }
        String cnpj = CompanyCertificateService.cnpjOf(company);
        A1Certificate certificate = openCertificate(company)
                .orElseThrow(() -> new BadRequestException(messageService.getMessage("error.sefaz.manifestation.no.certificate")));

        List<ManifestationDTOs.Result.Item> items = new ArrayList<>();
        for (int from = 0; from < keys.size(); from += SefazEventClient.MAX_PER_BATCH) {
            List<String> batch = keys.subList(from, Math.min(keys.size(), from + SefazEventClient.MAX_PER_BATCH));
            items.addAll(sendBatch(company, certificate, cnpj, type, type.needsJustification() ? justification : null, batch));
        }
        int ok = (int) items.stream().filter(ManifestationDTOs.Result.Item::ok).count();
        return new ManifestationDTOs.Result(ok, items.size() - ok, items);
    }

    private List<ManifestationDTOs.Result.Item> sendBatch(Company company, A1Certificate certificate, String cnpj,
                                                          Manifestation type, String justification, List<String> keys) {
        SefazEventResponse response;
        try {
            response = eventClient.send(certificate, cnpj,
                    keys.stream().map(key -> new EventRequest(key, type, justification)).toList());
        } catch (IOException | GeneralSecurityException e) {
            log.warn("Manifestation {} failed for company {}", type, company.getId(), e);
            return keys.stream().map(key -> new ManifestationDTOs.Result.Item(key, false, null,
                    "Falha ao falar com a SEFAZ: " + e.getMessage())).toList();
        }
        if (!response.processed()) {
            return keys.stream().map(key -> new ManifestationDTOs.Result.Item(key, false, response.cStat(),
                    "Lote recusado: " + response.xMotivo())).toList();
        }
        Map<String, SefazEventResponse.Result> byKey = response.results().stream()
                .filter(result -> result.accessKey() != null)
                .collect(Collectors.toMap(SefazEventResponse.Result::accessKey, Function.identity(), (a, b) -> a));
        LocalDateTime now = LocalDateTime.now();
        List<ManifestationDTOs.Result.Item> items = new ArrayList<>();
        for (String key : keys) {
            SefazEventResponse.Result result = byKey.get(key);
            if (result == null) {
                items.add(new ManifestationDTOs.Result.Item(key, false, null, "Sem retorno da SEFAZ para a nota."));
                continue;
            }
            boolean ok = result.registered() || result.alreadyDone();
            if (result.registered()) {
                eventService.record(company, new FiscalEventData(key, type.code, type.label, 1,
                        registeredAt(result.registeredAt(), now), result.protocol(), justification, true));
            }
            if (ok) {
                releaseSummary(company, key, now);
            }
            items.add(new ManifestationDTOs.Result.Item(key, ok, result.cStat(),
                    result.alreadyDone() ? "A nota já tinha essa manifestação." : result.xMotivo()));
        }
        return items;
    }

    /** Any manifestation releases the full XML: the summary is fetched by key on the next capture. */
    private void releaseSummary(Company company, String key, LocalDateTime now) {
        summaryRepository.findByCompany_IdAndAccessKey(company.getId(), key)
                .filter(summary -> !summary.isCompleted() && summary.getCienciaStatus() != CienciaStatus.ENVIADA)
                .ifPresent(summary -> {
                    summary.setCienciaStatus(CienciaStatus.ENVIADA);
                    summary.setCienciaAt(now);
                    summary.setCienciaDetail(null);
                    summaryRepository.save(summary);
                });
    }

    private Optional<CompanyCertificate> validCertificate(Company company) {
        return certificateService.resolve(company)
                .filter(stored -> stored.getValidUntil() != null && stored.getValidUntil().isAfter(LocalDateTime.now()));
    }

    private Optional<A1Certificate> openCertificate(Company company) {
        Optional<CompanyCertificate> stored = validCertificate(company);
        if (stored.isEmpty() || CompanyCertificateService.cnpjOf(company) == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(certificateService.open(stored.get()));
        } catch (IOException | GeneralSecurityException | IllegalStateException e) {
            log.warn("Could not open the certificate of company {} for the manifestation", company.getId(), e);
            return Optional.empty();
        }
    }

    private static ManifestationDTOs.Note note(String key, UUID id, String numero, String serie, String issuerName,
                                               String issuerDocument, LocalDateTime issueDate, java.math.BigDecimal total,
                                               boolean complete, boolean ciencia, LocalDateTime authorizedAt, LocalDate today) {
        LocalDate deadline = authorizedAt == null ? null : authorizedAt.toLocalDate().plusDays(DEADLINE_DAYS);
        long daysLeft = deadline == null ? 0 : ChronoUnit.DAYS.between(today, deadline);
        return new ManifestationDTOs.Note(key, id, numero, serie, issuerName, issuerDocument, issueDate, total, complete,
                ciencia, deadline, daysLeft);
    }

    /** nNF from the key (positions 26-34), without leading zeros. */
    static String numberOf(String key) {
        return key == null || key.length() != 44 ? null : key.substring(25, 34).replaceFirst("^0+(?=\\d)", "");
    }

    /** Series from the key (positions 23-25). */
    static String seriesOf(String key) {
        return key == null || key.length() != 44 ? null : key.substring(22, 25).replaceFirst("^0+(?=\\d)", "");
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
}
