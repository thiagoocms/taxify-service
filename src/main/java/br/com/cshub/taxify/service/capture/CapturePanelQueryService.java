package br.com.cshub.taxify.service.capture;

import br.com.cshub.taxify.common.CurrentUserService;
import br.com.cshub.taxify.common.DocumentNumbers;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.config.CapturePanelProperties;
import br.com.cshub.taxify.config.CapturePriorityProperties;
import br.com.cshub.taxify.config.ClockConfig;
import br.com.cshub.taxify.domain.capture.CapturePriorityRequest;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.dto.capture.CaptureCompanyDTO;
import br.com.cshub.taxify.dto.capture.CapturePanelResponseDTO;
import br.com.cshub.taxify.enumerated.CaptureChannel;
import br.com.cshub.taxify.enumerated.CaptureFailureCode;
import br.com.cshub.taxify.enumerated.CaptureSituation;
import br.com.cshub.taxify.enumerated.CaptureStage;
import br.com.cshub.taxify.enumerated.PlanServiceEnum;
import br.com.cshub.taxify.enumerated.UserProfileEnum;
import br.com.cshub.taxify.exception.ForbiddenException;
import br.com.cshub.taxify.exception.ResourceNotFoundException;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlSyncProperties;
import br.com.cshub.taxify.integration.sefaz.SefazDistClient;
import br.com.cshub.taxify.integration.sefaz.SefazProperties;
import br.com.cshub.taxify.repository.CapturePanelRepository;
import br.com.cshub.taxify.repository.CapturePanelRepository.CertificateRow;
import br.com.cshub.taxify.repository.CapturePanelRepository.CompanyRow;
import br.com.cshub.taxify.repository.CapturePanelRepository.QueueRow;
import br.com.cshub.taxify.repository.CapturePanelRepository.StageRow;
import br.com.cshub.taxify.repository.CapturePriorityRequestRepository;
import br.com.cshub.taxify.service.capture.CaptureSituationRules.DirectFacts;
import br.com.cshub.taxify.service.capture.CaptureSituationRules.Reason;
import br.com.cshub.taxify.service.capture.CaptureSituationRules.SiegFacts;
import br.com.cshub.taxify.service.capture.CaptureSituationRules.StageFacts;
import br.com.cshub.taxify.service.capture.CaptureSituationRules.Verdict;
import br.com.cshub.taxify.service.firmaccess.FirmAccess;
import br.com.cshub.taxify.service.firmaccess.FirmAccessService;
import br.com.cshub.taxify.validation.CapturePanelValidation.Filter;
import br.com.cshub.taxify.validation.CapturePanelValidation.ItemType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.text.Collator;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * O painel da captura de XML (spec 2026-11-27): monta o resumo, a saúde da fila e a página de empresas com um número
 * fixo de consultas (CP-RN-14) - a projeção do escopo, o direito (2), a fila da plataforma, a situação dos escritórios,
 * os pedidos abertos, os certificados e as etapas; e, só para a página, a última nota por origem (2), a recuperação e
 * os nomes de quem pediu. A situação, o resumo, o filtro, a ordem e o corte da página são feitos em memória, pelas
 * regras puras de {@link CaptureSituationRules}.
 *
 * <p>Relógio de Recife (CP-RN-04). As horas que a captura direta e a auditoria gravam no fuso da JVM
 * ({@code sefaz_dist_control}, {@code nfse_adn_control}, {@code created_date} das notas, {@code valid_until}) são
 * convertidas para Recife antes de comparar ou mostrar. Nunca devolve CNPJ completo, {@code last_detail}, valores ou
 * chaves de acesso.
 */
@Service
public class CapturePanelQueryService {

    private static final Logger log = LoggerFactory.getLogger(CapturePanelQueryService.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter SHORT_DATE_TIME = DateTimeFormatter.ofPattern("dd/MM HH:mm");
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm");
    private static final int CERTIFICATE_WARNING_DAYS = 30;

    private final CapturePanelRepository panelRepository;
    private final CapturePriorityRequestRepository priorityRepository;
    private final CaptureEntitlement entitlement;
    private final FirmAccessService firmAccessService;
    private final CurrentUserService currentUserService;
    private final CapturePanelProperties panelProperties;
    private final CapturePriorityProperties priorityProperties;
    private final FiscalXmlSyncProperties syncProperties;
    private final SefazProperties sefazProperties;
    private final MessageService messageService;
    private final Clock clock;

    public CapturePanelQueryService(CapturePanelRepository panelRepository, CapturePriorityRequestRepository priorityRepository,
                                    CaptureEntitlement entitlement, FirmAccessService firmAccessService,
                                    CurrentUserService currentUserService, CapturePanelProperties panelProperties,
                                    CapturePriorityProperties priorityProperties, FiscalXmlSyncProperties syncProperties,
                                    SefazProperties sefazProperties, MessageService messageService, Clock clock) {
        this.panelRepository = panelRepository;
        this.priorityRepository = priorityRepository;
        this.entitlement = entitlement;
        this.firmAccessService = firmAccessService;
        this.currentUserService = currentUserService;
        this.panelProperties = panelProperties;
        this.priorityProperties = priorityProperties;
        this.syncProperties = syncProperties;
        this.sefazProperties = sefazProperties;
        this.messageService = messageService;
        this.clock = clock;
    }

    /** Quem consulta: o escritório (null = plataforma, todos), a carteira (null = sem) e se é a visão da plataforma. */
    record Scope(UUID firmId, UUID portfolioUserId, boolean platform, UUID viewerFirmId) {
    }

    // ------------------------------------------------------------------ entry points

    /** {@code GET /capture-panel}: o escritório do usuário logado; o perfil não ADMIN só vê a carteira (CP-RN-01). */
    public CapturePanelResponseDTO firmPanel(Filter filter) {
        return panel(firmScope(), filter, null);
    }

    public CaptureCompanyDTO firmDetail(UUID companyId) {
        return detail(firmScope(), companyId);
    }

    /** {@code GET /platform/capture-panel}: todas as empresas, ou as de {@code firmId} (CP-RN-02). */
    public CapturePanelResponseDTO platformPanel(Filter filter, UUID firmId) {
        Scope scope = platformScope(firmId);
        log.info("capture.panel.viewed scope=platform by={} firm={}", viewerId(), firmId == null ? "all" : firmId);
        return panel(scope, filter, null);
    }

    public CaptureCompanyDTO platformDetail(UUID companyId) {
        Scope scope = platformScope(null);
        CaptureCompanyDTO detail = detail(scope, companyId);
        log.info("capture.panel.viewed scope=platform by={} firm={}", viewerId(), detail.firmId());
        return detail;
    }

    /** 404 com a visão da plataforma desligada (TAXIFY_CAPTURE_PANEL_PLATFORM_ENABLED=false). */
    public void assertPlatformEnabled() {
        if (!panelProperties.isPlatformEnabled()) {
            throw new ResourceNotFoundException("error.generic.not.found", messageService.getMessage("error.generic.not.found"));
        }
    }

    private Scope firmScope() {
        User user = currentUserService.getCurrentUser().orElseThrow(this::forbidden);
        if (user.getAccountingFirm() == null || user.getAccountingFirm().getId() == null) {
            // a context without a firm is never "every company" (CP-RN-01, R-02)
            throw forbidden();
        }
        UUID firmId = user.getAccountingFirm().getId();
        return new Scope(firmId, user.getProfile() == UserProfileEnum.ADMIN ? null : user.getId(), false, firmId);
    }

    private Scope platformScope(UUID firmId) {
        assertPlatformEnabled();
        UUID viewerFirm = currentUserService.getCurrentUser().map(user -> user.getAccountingFirm() == null ? null
                : user.getAccountingFirm().getId()).orElse(null);
        return new Scope(firmId, null, true, viewerFirm);
    }

    private String viewerId() {
        return currentUserService.getCurrentUser().map(user -> String.valueOf(user.getId())).orElse("unknown");
    }

    // ------------------------------------------------------------------ the pipeline

    private CapturePanelResponseDTO panel(Scope scope, Filter filter, UUID onlyCompany) {
        Context context = context(scope, onlyCompany);
        List<Evaluated> inScope = context.evaluated.stream()
                .filter(item -> filter.includeInactive() || item.row.active())
                .toList();

        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (CaptureSituation situation : List.of(CaptureSituation.EM_DIA, CaptureSituation.PENDENTE, CaptureSituation.ATRASADA,
                CaptureSituation.COM_ERRO, CaptureSituation.NUNCA_TENTADA, CaptureSituation.FORA_DO_PLANO, CaptureSituation.BLOQUEADA)) {
            byStatus.put(situation.name(), 0L);
        }
        LocalDateTime lastAttempt = null;
        int neverAttempted = 0;
        for (Evaluated item : inScope) {
            byStatus.merge(item.overall.situation().name(), 1L, Long::sum);
            if (item.row.lastAttemptAt() != null && (lastAttempt == null || item.row.lastAttemptAt().isAfter(lastAttempt))) {
                lastAttempt = item.row.lastAttemptAt();
            }
            if (item.siegVerdict.situation() == CaptureSituation.NUNCA_TENTADA) {
                neverAttempted++;
            }
        }

        List<Evaluated> filtered = inScope.stream().filter(item -> matches(item, filter, context)).sorted(order(filter)).toList();
        // long: a huge page number never overflows into a negative index
        int from = (int) Math.min(filtered.size(), (long) filter.page() * filter.size());
        int to = (int) Math.min(filtered.size(), (long) from + filter.size());
        List<Evaluated> page = filtered.subList(from, to);
        PageExtras extras = pageExtras(page, scope, false);
        List<CaptureCompanyDTO> content = page.stream().map(item -> toDto(item, context, scope, extras, false)).toList();
        int totalPages = (int) Math.ceil(filtered.size() / (double) filter.size());

        return new CapturePanelResponseDTO(
                new CapturePanelResponseDTO.Summary(byStatus, offset(lastAttempt)),
                queueDto(context, scope, neverAttempted),
                new CapturePanelResponseDTO.Page(content, filtered.size(), totalPages, filter.page(), filter.size()));
    }

    private CaptureCompanyDTO detail(Scope scope, UUID companyId) {
        Context context = context(scope, companyId);
        Evaluated item = context.evaluated.stream().filter(found -> found.row.companyId().equals(companyId)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("error.Company.not.found",
                        messageService.getMessage("error.Company.not.found")));
        return toDto(item, context, scope, pageExtras(List.of(item), scope, true), true);
    }

    /** Everything evaluated for the scope (fixed number of queries, see the class doc). */
    private Context context(Scope scope, UUID onlyCompany) {
        LocalDateTime now = LocalDateTime.now(clock);
        CaptureSituationRules.Clock rulesClock = new CaptureSituationRules.Clock(now, now.toLocalDate(),
                panelProperties.getSiegFreshHours(), panelProperties.getDirectFreshHours());

        // 1. the scope's projection
        List<CompanyRow> rows = panelRepository.companies(scope.firmId(), scope.portfolioUserId(), onlyCompany);
        // the entitlement on the schedulers' day (2 queries)
        Map<UUID, Set<PlanServiceEnum>> services = entitlement.currentServices(entitlement.schedulerToday());
        // 3. the platform's SIEG queue (light)
        List<QueueRow> queueRows = panelRepository.queue();
        // 2. each firm's access, once per firm (the RoutineGate rule)
        Set<UUID> firmIds = new HashSet<>();
        rows.forEach(row -> firmIds.add(row.firmId()));
        queueRows.forEach(row -> {
            if (row.firmId() != null) {
                firmIds.add(row.firmId());
            }
        });
        Map<UUID, FirmAccess> access = firmAccessService.accessOf(firmIds);
        Function<UUID, Boolean> allowed = firmId -> firmId != null && access.containsKey(firmId)
                && access.get(firmId).allowsAutomaticRoutines();

        // the queue as FiscalXmlSyncScheduler builds it: gate + SIEG entitlement, then queueOrder()
        List<QueueRow> eligible = queueRows.stream()
                .filter(row -> allowed.apply(row.firmId()))
                .filter(row -> CaptureEntitlement.sieg(services.get(row.companyId())))
                .sorted(queueOrder())
                .toList();
        List<CapturePriorityRequest> open = priorityRepository.findOpenOrdered();
        Map<UUID, Integer> priorityPosition = new HashMap<>();
        Map<UUID, CapturePriorityRequest> openByCompany = new HashMap<>();
        for (CapturePriorityRequest request : open) {
            if (!openByCompany.containsKey(request.getCompanyId())) {
                openByCompany.put(request.getCompanyId(), request);
                priorityPosition.put(request.getCompanyId(), priorityPosition.size());
            }
        }
        Map<UUID, Integer> normalPosition = new HashMap<>();
        for (QueueRow row : eligible) {
            if (!openByCompany.containsKey(row.companyId())) {
                normalPosition.put(row.companyId(), normalPosition.size());
            }
        }
        int batch = Math.max(1, syncProperties.getBatchSize());
        int configuredSlots = priorityProperties.isEnabled() ? priorityProperties.getSlotsPerTick() : 0;
        int slotsInUse = CaptureQueueMath.slotsInUse(batch, configuredSlots, openByCompany.size());
        CaptureQueueClock queueClock = CaptureQueueClock.of(syncProperties.getCron(), now, ZoneId.systemDefault());

        Context context = new Context(now, rulesClock, scope, eligible, openByCompany, priorityPosition, normalPosition, batch,
                configuredSlots, slotsInUse, queueClock, services);

        // certificates of the root (own or head office) and the stages
        Map<UUID, Map<String, List<CertificateRow>>> certificatesByFirmRoot = new HashMap<>();
        Map<UUID, CertificateRow> ownCertificate = new HashMap<>();
        for (CertificateRow certificate : panelRepository.certificates(scope.firmId())) {
            ownCertificate.put(certificate.companyId(), certificate);
            String cnpj = cnpj(certificate.document());
            if (cnpj != null && certificate.firmId() != null) {
                certificatesByFirmRoot.computeIfAbsent(certificate.firmId(), key -> new HashMap<>())
                        .computeIfAbsent(cnpj.substring(0, 8), key -> new ArrayList<>()).add(certificate);
            }
        }
        Map<UUID, Map<CaptureStage, StageFacts>> stagesByCompany = new HashMap<>();
        for (StageRow stage : panelRepository.stages(scope.firmId())) {
            StageFacts facts = stageFacts(stage);
            if (facts != null) {
                stagesByCompany.computeIfAbsent(stage.companyId(), key -> new EnumMap<>(CaptureStage.class)).put(facts.stage(), facts);
            }
        }

        for (CompanyRow row : rows) {
            context.evaluated.add(evaluate(row, context, allowed.apply(row.firmId()),
                    stagesByCompany.getOrDefault(row.companyId(), Map.of()),
                    certificateOf(row, ownCertificate, certificatesByFirmRoot)));
        }
        return context;
    }

    /** CompanyCertificateService#resolve: the company's own certificate, else one of the same CNPJ root in the firm. */
    private CertificateRow certificateOf(CompanyRow row, Map<UUID, CertificateRow> own,
                                         Map<UUID, Map<String, List<CertificateRow>>> byFirmRoot) {
        CertificateRow mine = own.get(row.companyId());
        if (mine != null) {
            return mine;
        }
        String cnpj = cnpj(row.document());
        if (cnpj == null) {
            return null;
        }
        List<CertificateRow> sameRoot = byFirmRoot.getOrDefault(row.firmId(), Map.of()).getOrDefault(cnpj.substring(0, 8), List.of());
        LocalDateTime now = LocalDateTime.now();
        return sameRoot.stream()
                .filter(certificate -> certificate.validUntil() != null)
                .max(Comparator.comparing((CertificateRow certificate) -> certificate.validUntil().isAfter(now))
                        .thenComparing(CertificateRow::validUntil))
                .orElse(null);
    }

    private Evaluated evaluate(CompanyRow row, Context context, boolean firmAllowed, Map<CaptureStage, StageFacts> stages,
                               CertificateRow certificate) {
        Set<PlanServiceEnum> services = context.services.get(row.companyId());
        boolean hasSubscription = services != null;
        Integer position = context.openByCompany.containsKey(row.companyId())
                ? context.priorityPosition.get(row.companyId())
                : context.normalPosition.get(row.companyId());
        // a side is verified only once a sync wrote its window (the received side of a row only seeded has no check)
        LocalDateTime receivedCheckedAt = row.windowFrom() == null ? null : row.receivedCheckedAt();

        SiegFacts sieg = new SiegFacts(firmAllowed, CaptureEntitlement.sieg(services), hasSubscription, row.lastAttemptAt(),
                row.receivedTotalSieg(), row.receivedImported(), receivedCheckedAt, row.issuedTotalSieg(), row.issuedImported(),
                row.issuedCheckedAt(), row.receivedEventsOn(), row.issuedEventsOn(), row.serviceInvoicesOn(),
                row.receivedPrevMonth(), row.receivedPrevTotalSieg(), row.receivedPrevImported(), row.issuedPrevMonth(),
                row.issuedPrevTotalSieg(), row.issuedPrevImported(), stages, position);

        String cnpj = cnpj(row.document());
        boolean ufMissing = SefazDistClient.ufCode(row.uf()) == null;
        LocalDate certificateUntil = certificate == null ? null : jvmToRecife(certificate.validUntil()).toLocalDate();
        DirectFacts sefaz = new DirectFacts(CaptureChannel.SEFAZ, firmAllowed, CaptureEntitlement.sefaz(services), hasSubscription,
                ufMissing, !ufMissing && sefazProperties.coversUf(row.uf()), cnpj == null, certificate != null, certificateUntil,
                row.sefazRow(), jvmToRecife(row.sefazLastRunAt()), row.sefazLastStatus(), stages.get(CaptureStage.SEFAZ_DIST));
        DirectFacts adn = new DirectFacts(CaptureChannel.ADN, firmAllowed, CaptureEntitlement.adn(services), hasSubscription,
                false, true, cnpj == null, certificate != null, certificateUntil,
                row.adnRow(), jvmToRecife(row.adnLastRunAt()), row.adnLastStatus(), stages.get(CaptureStage.NFSE_ADN));

        Verdict siegVerdict = CaptureSituationRules.sieg(sieg, context.rulesClock);
        Verdict sefazVerdict = CaptureSituationRules.direct(sefaz, context.rulesClock);
        Verdict adnVerdict = CaptureSituationRules.direct(adn, context.rulesClock);
        Verdict overall = CaptureSituationRules.company(firmAllowed, siegVerdict, sefazVerdict, adnVerdict);
        LocalDateTime estimate = estimate(row.companyId(), context);
        return new Evaluated(row, sieg, sefaz, adn, siegVerdict, sefazVerdict, adnVerdict, overall, stages, certificate,
                position, estimate, normalize(row.name()), context.openByCompany.get(row.companyId()));
    }

    /** CP-RN-12: próxima execução + floor(posição / vagas) × intervalo (a posição da API, que começa em 0). */
    private LocalDateTime estimate(UUID companyId, Context context) {
        if (context.queueClock == null) {
            return null;
        }
        Integer priority = context.openByCompany.containsKey(companyId) ? context.priorityPosition.get(companyId) : null;
        if (priority != null) {
            return context.queueClock.afterRounds(CaptureQueueMath.roundsForPriorityPosition(priority, context.batch,
                    Math.max(1, context.configuredSlots)));
        }
        Integer normal = context.normalPosition.get(companyId);
        if (normal == null) {
            return null;
        }
        return context.queueClock.afterRounds(CaptureQueueMath.roundsForNormalPosition(normal, context.batch, context.slotsInUse));
    }

    /** FiscalXmlSyncScheduler#queueOrder over the light rows. */
    static Comparator<QueueRow> queueOrder() {
        return Comparator.comparing(QueueRow::lastAttemptAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(QueueRow::verifiedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(row -> row.controlId() == null ? "" : row.controlId().toString())
                .thenComparing(row -> row.companyId() == null ? "" : row.companyId().toString());
    }

    // ------------------------------------------------------------------ filter and order (CP-RN-13, CP-RN-14)

    private boolean matches(Evaluated item, Filter filter, Context context) {
        if (!filter.situations().isEmpty()) {
            CaptureSituation situation = filter.channel() == null ? item.overall.situation() : switch (filter.channel()) {
                case SIEG -> item.siegVerdict.situation();
                case SEFAZ -> item.sefazVerdict.situation();
                case ADN -> item.adnVerdict.situation();
            };
            if (!filter.situations().contains(situation)) {
                return false;
            }
        }
        if (filter.type() != null && !matchesType(item, filter.type(), context)) {
            return false;
        }
        if (filter.search() != null && !item.normalizedName.contains(normalize(filter.search()))) {
            return false;
        }
        return true;
    }

    private static boolean matchesType(Evaluated item, ItemType type, Context context) {
        return switch (type) {
            case NFE_RECEBIDAS -> CaptureSituationRules.sideNeedsAttention(item.siegFacts, false, context.rulesClock);
            case NFE_EMITIDAS -> CaptureSituationRules.sideNeedsAttention(item.siegFacts, true, context.rulesClock);
            case EVENTOS -> CaptureSituationRules.eventsNeedAttention(item.siegFacts, context.rulesClock);
            case NFSE -> CaptureSituationRules.serviceInvoicesNeedAttention(item.siegFacts, item.adnVerdict, context.rulesClock);
        };
    }

    private static Comparator<Evaluated> order(Filter filter) {
        Collator collator = Collator.getInstance(Locale.forLanguageTag("pt-BR"));
        collator.setStrength(Collator.PRIMARY);
        Comparator<Evaluated> byName = Comparator.comparing((Evaluated item) -> item.row.name() == null ? "" : item.row.name(), collator)
                .thenComparing(item -> item.row.companyId().toString());
        return switch (filter.sort()) {
            case NAME -> byName;
            case NEXT_ATTEMPT -> Comparator.comparing((Evaluated item) -> item.estimate, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(byName);
            case STATUS -> Comparator.comparing((Evaluated item) -> item.overall.situation().severity(), Comparator.reverseOrder())
                    .thenComparing(item -> item.row.lastAttemptAt(), Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(byName);
        };
    }

    /** Minúsculas e sem acento: "Têxtil" casa com "textil" (CP-RN-13). */
    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ page extras (step 4)

    record PageExtras(Map<UUID, Map<String, LocalDateTime>> invoices, Map<UUID, Map<String, LocalDateTime>> serviceInvoices,
                      Map<UUID, Set<YearMonth>> recoveries, Map<String, String> userNames) {
    }

    /** @param detail the detail's extras too (last note per source, recovery) - the list only needs the requesters' names */
    private PageExtras pageExtras(List<Evaluated> page, Scope scope, boolean detail) {
        List<UUID> ids = detail ? page.stream().map(item -> item.row.companyId()).toList() : List.of();
        Map<UUID, Map<String, LocalDateTime>> invoices = new HashMap<>();
        Map<UUID, Map<String, LocalDateTime>> serviceInvoices = new HashMap<>();
        for (var note : panelRepository.lastDocuments(ids)) {
            (note.service() ? serviceInvoices : invoices).computeIfAbsent(note.companyId(), key -> new HashMap<>())
                    .merge(note.source(), note.at(), CapturePanelQueryService::later);
        }
        Map<UUID, Set<YearMonth>> recoveries = new HashMap<>();
        for (var task : panelRepository.openRecoveries(ids)) {
            if (task.periodStart() != null) {
                recoveries.computeIfAbsent(task.companyId(), key -> new TreeSet<>()).add(YearMonth.from(task.periodStart()));
            }
        }
        // "Pedido por {nome}" only for the same firm (CP-RN-19); the platform's own requests show "pela CS Hub"
        Map<String, String> names = new HashMap<>();
        if (!scope.platform() && scope.viewerFirmId() != null) {
            Set<String> requesters = new HashSet<>();
            for (Evaluated item : page) {
                CapturePriorityRequest request = item.request();
                if (request != null && !request.isFromPlatform()) {
                    requesters.add(request.getRequestedBy());
                }
            }
            for (Object[] row : panelRepository.userNames(requesters, scope.viewerFirmId())) {
                names.put((String) row[0], (String) row[1]);
            }
        }
        return new PageExtras(invoices, serviceInvoices, recoveries, names);
    }

    private static LocalDateTime later(LocalDateTime a, LocalDateTime b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isAfter(b) ? a : b;
    }

    // ------------------------------------------------------------------ DTOs

    private CaptureCompanyDTO toDto(Evaluated item, Context context, Scope scope, PageExtras extras, boolean withDetail) {
        CompanyRow row = item.row;
        CaptureCompanyDTO.Channels channels = new CaptureCompanyDTO.Channels(siegDto(item, context, scope, extras),
                directDto(item.sefazVerdict, item.sefazFacts, item.certificate, row, row.sefazLastRunAt(), row.sefazLastStatus(),
                        row.sefazNextQueryAt(), context),
                directDto(item.adnVerdict, item.adnFacts, item.certificate, row, row.adnLastRunAt(), row.adnLastStatus(),
                        row.adnNextQueryAt(), context));
        String statusReason = text(item.overall.reason());
        return new CaptureCompanyDTO(row.companyId(), row.name(), establishment(row.document()), row.active(),
                scope.platform() ? row.firmId() : null, scope.platform() ? row.firmName() : null,
                item.overall.situation().name(), statusReason, channels, lastFailureDto(item, statusReason),
                withDetail ? stagesDto(item) : null, withDetail ? lastDocuments(item, extras) : null,
                withDetail ? recoveringMonths(item, extras) : null, withDetail ? memoryDto(item, extras, scope) : null);
    }

    private CaptureCompanyDTO.Sieg siegDto(Evaluated item, Context context, Scope scope, PageExtras extras) {
        CompanyRow row = item.row;
        SiegFacts facts = item.siegFacts;
        YearMonth current = YearMonth.from(context.rulesClock.today());
        CaptureCompanyDTO.Side received = side(row.receivedImported(), row.receivedTotalSieg(), row.windowFrom(), row.windowTo(),
                facts.receivedCheckedAt(), row.receivedPrevMonth(), row.receivedPrevImported(), row.receivedPrevTotalSieg(),
                "received", current, row.receivedStoredAnySource());
        CaptureCompanyDTO.Side issued = row.issuedCheckedAt() == null && row.issuedTotalSieg() == null ? null
                : side(row.issuedImported() == null ? 0 : row.issuedImported(), row.issuedTotalSieg(), row.windowFrom(),
                row.windowTo(), row.issuedCheckedAt(), row.issuedPrevMonth(), row.issuedPrevImported(), row.issuedPrevTotalSieg(),
                "issued", current, row.issuedStoredAnySource());
        CapturePriorityRequest request = item.request();
        CaptureCompanyDTO.Priority priority = null;
        if (request != null) {
            Integer position = context.priorityPosition.get(row.companyId());
            String name = scope.platform() || request.isFromPlatform() ? null : extras.userNames().get(request.getRequestedBy());
            priority = new CaptureCompanyDTO.Priority(request.getId(), request.getStatus().name(), offset(request.getRequestedAt()),
                    name, request.isFromPlatform(), position == null ? 0 : position,
                    scope.platform() ? null : offset(item.estimate), requestedByLabel(request, scope, extras));
        }
        // the platform sees the position only; the firm's ADMIN, the position and the estimate (CP-RN-12)
        OffsetDateTime estimate = scope.platform() ? null : offset(item.estimate);
        return new CaptureCompanyDTO.Sieg(item.siegVerdict.situation().name(), text(item.siegVerdict.reason()),
                offset(row.lastAttemptAt()), offset(facts.verifiedAt()), item.position, estimate, received, issued,
                new CaptureCompanyDTO.EventsCheckedOn(row.receivedEventsOn(), row.issuedEventsOn()), row.serviceInvoicesOn(),
                priority);
    }

    private CaptureCompanyDTO.Side side(int imported, Integer totalSieg, LocalDate from, LocalDate to, LocalDateTime checkedAt,
                                        String prevMonth, Integer prevImported, Integer prevTotal, String sideKey, YearMonth current,
                                        Integer storedAnySource) {
        String month = checkedAt == null ? (from == null ? null : YearMonth.from(from).toString()) : YearMonth.from(checkedAt).toString();
        Integer missing = totalSieg == null || checkedAt == null ? null : Math.max(0, totalSieg - imported);
        // "vindas do SIEG / total do SIEG" make the situation; the notes from any source go next to them (2026-11-27)
        String memory = checkedAt == null || from == null || to == null ? null
                : message(storedAnySource == null ? "capture.memory.side" : "capture.memory.side.stored", String.valueOf(imported),
                from.format(DATE), to.format(DATE), totalSieg == null ? "?" : String.valueOf(totalSieg), checkedAt.format(DATE_TIME),
                storedAnySource == null ? "" : String.valueOf(storedAnySource));
        CaptureCompanyDTO.PreviousMonth previous = null;
        if (prevMonth != null && !prevMonth.isBlank()) {
            YearMonth closed = YearMonth.parse(prevMonth.strip());
            // shown until the next turn of the month (CP-RN-22)
            if (closed.isBefore(current)) {
                int prevMissing = prevTotal == null ? 0 : Math.max(0, prevTotal - (prevImported == null ? 0 : prevImported));
                previous = new CaptureCompanyDTO.PreviousMonth(closed.toString(), prevImported, prevTotal, prevMissing,
                        message("capture.memory.previous." + sideKey, monthLabel(closed),
                                String.valueOf(prevImported == null ? 0 : prevImported), String.valueOf(prevTotal == null ? 0 : prevTotal)));
            }
        }
        return new CaptureCompanyDTO.Side(imported, totalSieg, from, to, offset(checkedAt), month, missing, previous, memory,
                storedAnySource);
    }

    private CaptureCompanyDTO.Direct directDto(Verdict verdict, DirectFacts facts, CertificateRow certificate, CompanyRow row,
                                               LocalDateTime lastRunAt, String lastStatus, LocalDateTime nextQueryAt, Context context) {
        if (verdict.situation() == CaptureSituation.FORA_DO_PLANO && !facts.planHasService()) {
            return new CaptureCompanyDTO.Direct(verdict.situation().name(), text(verdict.reason()), null, null, null, null, null, null);
        }
        CaptureCompanyDTO.Certificate certificateDto;
        if (certificate == null) {
            certificateDto = new CaptureCompanyDTO.Certificate(false, false, null, false, false, null);
        } else {
            LocalDate until = facts.certificateValidUntil();
            boolean expired = until != null && until.isBefore(context.rulesClock.today());
            boolean expiringSoon = CaptureSituationRules.expiresSoon(until, context.rulesClock.today(), CERTIFICATE_WARNING_DAYS);
            String warning = expiringSoon ? message("capture.certificate.expires.soon", until.format(DATE)) : null;
            certificateDto = new CaptureCompanyDTO.Certificate(true, !certificate.companyId().equals(row.companyId()), until,
                    expired, expiringSoon, warning);
        }
        CaptureFailureCode code = verdict.situation() == CaptureSituation.COM_ERRO ? verdict.failureCode() : null;
        return new CaptureCompanyDTO.Direct(verdict.situation().name(), text(verdict.reason()), offset(jvmToRecife(lastRunAt)),
                lastStatus, offset(jvmToRecife(nextQueryAt)), certificateDto, code == null ? null : code.name(),
                code == null ? null : text(verdict.reason()));
    }

    private CaptureCompanyDTO.LastFailure lastFailureDto(Evaluated item, String statusReason) {
        StageFacts stage = CaptureSituationRules.lastFailure(item.stages);
        if (stage == null) {
            return null;
        }
        String message = failureText(stage, item);
        return new CaptureCompanyDTO.LastFailure(stage.stage().name(), offset(stage.lastFailureAt()),
                stage.failureCode() == null ? null : stage.failureCode().name(), stage.providerStatus(),
                message, !stage.failure(), stage.consecutiveFailures(), offset(stage.lastSuccessAt()), stageMemory(stage),
                // the open failure that is already the company's statusReason: the screen shows it once
                stage.failure() && message != null && message.equals(statusReason));
    }

    /** The failure's message with the certificate's validity (CERT_EXPIRED) - the same text as the channel's. */
    private String failureText(StageFacts stage, Evaluated item) {
        if (stage.failureCode() == null) {
            return null;
        }
        LocalDate until = item.sefazFacts == null ? null : item.sefazFacts.certificateValidUntil();
        return text(CaptureSituationRules.failureReason(stage, until));
    }

    /** "Falhou N vezes seguidas; último sucesso em ..." / "... sem sucesso registrado" / "última falha em ... (resolvida)". */
    private String stageMemory(StageFacts facts) {
        if (facts.failure()) {
            if (facts.consecutiveFailures() <= 1) {
                return null;
            }
            return facts.lastSuccessAt() == null
                    ? message("capture.memory.failures.in.a.row.never.succeeded", String.valueOf(facts.consecutiveFailures()))
                    : message("capture.memory.failures.in.a.row", String.valueOf(facts.consecutiveFailures()),
                    facts.lastSuccessAt().format(DATE_TIME));
        }
        return facts.lastFailureAt() == null ? null
                : message("capture.memory.failure.resolved", facts.lastFailureAt().format(SHORT_DATE_TIME));
    }

    private List<CaptureCompanyDTO.Stage> stagesDto(Evaluated item) {
        List<CaptureCompanyDTO.Stage> stages = new ArrayList<>();
        for (CaptureStage stage : CaptureStage.values()) {
            StageFacts facts = item.stages.get(stage);
            if (facts == null) {
                continue;
            }
            String failureMessage = failureText(facts, item);
            String warningMessage = facts.warningCode() == null ? null
                    : message(facts.warningCode().messageKey(), facts.warningCount() == null ? "" : String.valueOf(facts.warningCount()));
            String memory = stageMemory(facts);
            stages.add(new CaptureCompanyDTO.Stage(stage.name(), facts.failure() ? "FAILURE" : "SUCCESS", offset(facts.outcomeAt()),
                    offset(facts.lastSuccessAt()), offset(facts.lastFailureAt()),
                    facts.failureCode() == null ? null : facts.failureCode().name(), facts.providerStatus(), failureMessage,
                    !facts.failure(), facts.consecutiveFailures(), facts.warningCode() == null ? null : facts.warningCode().name(),
                    facts.warningCount(), warningMessage, memory));
        }
        return stages;
    }

    private CaptureCompanyDTO.LastDocuments lastDocuments(Evaluated item, PageExtras extras) {
        UUID companyId = item.row.companyId();
        Map<String, LocalDateTime> invoices = extras.invoices().getOrDefault(companyId, Map.of());
        Map<String, LocalDateTime> services = extras.serviceInvoices().getOrDefault(companyId, Map.of());
        return new CaptureCompanyDTO.LastDocuments(
                new CaptureCompanyDTO.Nfe(offset(jvmToRecife(invoices.get("SIEG"))), offset(jvmToRecife(invoices.get("SEFAZ_DIST"))),
                        offset(jvmToRecife(invoices.get("UPLOAD")))),
                new CaptureCompanyDTO.Nfse(offset(jvmToRecife(services.get("SIEG"))), offset(jvmToRecife(services.get("ADN"))),
                        offset(jvmToRecife(services.get("UPLOAD")))));
    }

    private static List<String> recoveringMonths(Evaluated item, PageExtras extras) {
        return extras.recoveries().getOrDefault(item.row.companyId(), Set.of()).stream().map(YearMonth::toString).toList();
    }

    private CaptureCompanyDTO.Memory memoryDto(Evaluated item, PageExtras extras, Scope scope) {
        List<String> months = recoveringMonths(item, extras);
        String recovery = months.isEmpty() ? null : message("capture.memory.recovering", joinWithAnd(months));
        String queue = item.position == null ? null : item.estimate == null || scope.platform()
                ? message("capture.memory.queue.no.estimate", String.valueOf(item.position + 1))
                : message("capture.memory.queue", String.valueOf(item.position + 1), item.estimate.format(SHORT_DATE_TIME));
        String verification = item.siegFacts.receivedCheckedAt() == null || item.row.issuedCheckedAt() == null ? null
                : message("capture.memory.verification", item.siegFacts.receivedCheckedAt().format(SHORT_DATE_TIME),
                item.row.issuedCheckedAt().format(SHORT_DATE_TIME));
        return new CaptureCompanyDTO.Memory(queue, verification, recovery);
    }

    private String joinWithAnd(List<String> values) {
        if (values.size() == 1) {
            return values.getFirst();
        }
        return String.join(", ", values.subList(0, values.size() - 1)) + " " + message("capture.memory.and") + " " + values.getLast();
    }

    private String requestedByLabel(CapturePriorityRequest request, Scope scope, PageExtras extras) {
        String at = request.getRequestedAt() == null ? "" : request.getRequestedAt().format(HOUR);
        if (request.isFromPlatform()) {
            return message("capture.priority.by.platform", at);
        }
        if (scope.platform()) {
            return message("capture.priority.by.firm", at);
        }
        String name = extras.userNames().get(request.getRequestedBy());
        return name == null ? message("capture.priority.by.firm", at) : message("capture.priority.by.user", name, at);
    }

    private CapturePanelResponseDTO.Queue queueDto(Context context, Scope scope, int neverAttemptedInScope) {
        LocalDateTime newest = null;
        LocalDateTime oldest = null;
        for (QueueRow row : context.eligible) {
            LocalDateTime attempt = row.lastAttemptAt();
            if (attempt == null) {
                continue;
            }
            if (newest == null || attempt.isAfter(newest)) {
                newest = attempt;
            }
            if (oldest == null || attempt.isBefore(oldest)) {
                oldest = attempt;
            }
        }
        String health;
        LocalDateTime stalledSince = null;
        if (!syncProperties.isEnabled()) {
            health = "DISABLED";
        } else if (newest == null) {
            health = context.eligible.isEmpty() ? "RUNNING" : "STALLED";
        } else if (Duration.between(newest, context.now).toMinutes() >= panelProperties.getStalledMinutes()) {
            health = "STALLED";
            stalledSince = newest;
        } else {
            health = "RUNNING";
        }
        Long observed = oldest == null ? null : Duration.between(oldest, context.now).toMinutes();
        Long theoretical = context.queueClock == null ? null
                : CaptureQueueMath.theoreticalLapRounds(context.eligible.size(), context.batch, context.slotsInUse)
                * context.queueClock.intervalMinutes();
        if (!scope.platform()) {
            // CP-RN-12: the firm's ADMIN sees only the state (running, stalled, off) - no platform-wide numbers or times
            stalledSince = null;
            observed = null;
            theoretical = null;
        }
        return new CapturePanelResponseDTO.Queue(health, offset(stalledSince), observed, neverAttemptedInScope, theoretical,
                context.queueClock == null ? null : offset(context.queueClock.nextTick()),
                context.queueClock == null ? null : context.queueClock.intervalMinutes(), context.batch, context.slotsInUse,
                scope.platform() ? context.openByCompany.size() : null);
    }

    // ------------------------------------------------------------------ helpers

    private static StageFacts stageFacts(StageRow row) {
        CaptureStage stage;
        try {
            stage = CaptureStage.valueOf(row.stage());
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
        return new StageFacts(stage, "FAILURE".equals(row.lastOutcome()), row.lastOutcomeAt(), row.lastSuccessAt(),
                row.lastFailureAt(), CaptureFailureCode.parse(row.failureCode()),
                CaptureFailureClassifier.providerStatus(row.providerStatus()), CaptureFailureCode.parse(row.warningCode()),
                row.warningCount(), row.consecutiveFailures());
    }

    /** Only-digits CNPJ (14), or null. */
    static String cnpj(String document) {
        String digits = DocumentNumbers.onlyDigits(document);
        return digits != null && digits.length() == 14 ? digits : null;
    }

    /** O número do estabelecimento ({@code 0001}) - nunca o CNPJ inteiro. */
    static String establishment(String document) {
        String cnpj = cnpj(document);
        return cnpj == null ? null : cnpj.substring(8, 12);
    }

    /** Uma hora gravada no fuso da JVM (LocalDateTime.now() da captura direta, auditoria) em horário de Recife. */
    static LocalDateTime jvmToRecife(LocalDateTime jvm) {
        return jvm == null ? null : jvm.atZone(ZoneId.systemDefault()).withZoneSameInstant(ClockConfig.RECIFE).toLocalDateTime();
    }

    static OffsetDateTime offset(LocalDateTime recife) {
        return recife == null ? null : recife.atZone(ClockConfig.RECIFE).toOffsetDateTime();
    }

    private String text(Reason reason) {
        if (reason == null) {
            return null;
        }
        String[] args = new String[reason.args().length];
        for (int i = 0; i < args.length; i++) {
            Object arg = reason.args()[i];
            args[i] = arg == null ? "" : arg instanceof LocalDate date ? date.format(DATE)
                    : arg instanceof YearMonth month ? monthLabel(month) : String.valueOf(arg);
        }
        return message(reason.key(), args);
    }

    /** "nov/2026". */
    private String monthLabel(YearMonth month) {
        return message("capture.month." + month.getMonthValue()) + "/" + month.getYear();
    }

    private String message(String key, String... args) {
        return args.length == 0 ? messageService.getMessage(key) : messageService.getMessage(key, args);
    }

    private ForbiddenException forbidden() {
        return new ForbiddenException("error.access.denied", messageService.getMessage("error.access.denied"));
    }

    // ------------------------------------------------------------------ internal state

    private static final class Context {
        final LocalDateTime now;
        final CaptureSituationRules.Clock rulesClock;
        final Scope scope;
        final List<QueueRow> eligible;
        final Map<UUID, CapturePriorityRequest> openByCompany;
        final Map<UUID, Integer> priorityPosition;
        final Map<UUID, Integer> normalPosition;
        final int batch;
        final int configuredSlots;
        final int slotsInUse;
        final CaptureQueueClock queueClock;
        final Map<UUID, Set<PlanServiceEnum>> services;
        final List<Evaluated> evaluated = new ArrayList<>();

        Context(LocalDateTime now, CaptureSituationRules.Clock rulesClock, Scope scope, List<QueueRow> eligible,
                Map<UUID, CapturePriorityRequest> openByCompany, Map<UUID, Integer> priorityPosition,
                Map<UUID, Integer> normalPosition, int batch, int configuredSlots, int slotsInUse, CaptureQueueClock queueClock,
                Map<UUID, Set<PlanServiceEnum>> services) {
            this.now = now;
            this.rulesClock = rulesClock;
            this.scope = scope;
            this.eligible = eligible;
            this.openByCompany = openByCompany;
            this.priorityPosition = priorityPosition;
            this.normalPosition = normalPosition;
            this.batch = batch;
            this.configuredSlots = configuredSlots;
            this.slotsInUse = slotsInUse;
            this.queueClock = queueClock;
            this.services = services;
        }
    }

    private record Evaluated(CompanyRow row, SiegFacts siegFacts, DirectFacts sefazFacts, DirectFacts adnFacts,
                             Verdict siegVerdict, Verdict sefazVerdict, Verdict adnVerdict, Verdict overall,
                             Map<CaptureStage, StageFacts> stages, CertificateRow certificate, Integer position,
                             LocalDateTime estimate, String normalizedName, CapturePriorityRequest priorityRequest) {

        CaptureSituation siegSituation() {
            return siegVerdict.situation();
        }

        Verdict sieg() {
            return siegVerdict;
        }

        CapturePriorityRequest request() {
            return priorityRequest;
        }
    }
}
