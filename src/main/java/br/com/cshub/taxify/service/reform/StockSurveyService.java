package br.com.cshub.taxify.service.reform;

import br.com.cshub.taxify.common.CurrentUserService;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.NamedLocks;
import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.config.StockSurveyProperties;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.reform.stock.StockCreditSurvey;
import br.com.cshub.taxify.domain.reform.stock.StockCreditSurveyEvent;
import br.com.cshub.taxify.domain.reform.stock.StockSurveyCalendar;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.dto.reform.StockSurveyDetailDTO;
import br.com.cshub.taxify.dto.reform.StockSurveyEventDTO;
import br.com.cshub.taxify.dto.reform.StockSurveyFilter;
import br.com.cshub.taxify.dto.reform.StockSurveyListDTO;
import br.com.cshub.taxify.dto.reform.StockSurveyRowDTO;
import br.com.cshub.taxify.dto.reform.StockSurveyStatusChangeDTO;
import br.com.cshub.taxify.exception.ConflictException;
import br.com.cshub.taxify.exception.ForbiddenException;
import br.com.cshub.taxify.exception.ResourceNotFoundException;
import br.com.cshub.taxify.mapper.StockSurveyMapper;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.repository.StockCreditSurveyEventRepository;
import br.com.cshub.taxify.repository.StockCreditSurveyRepository;
import br.com.cshub.taxify.repository.StockSurveyListRepository;
import br.com.cshub.taxify.repository.StockSurveyListRepository.Scope;
import br.com.cshub.taxify.repository.StockSurveySourceRepository;
import br.com.cshub.taxify.repository.StockSurveySourceRepository.CompanyRef;
import br.com.cshub.taxify.validation.StockSurveyValidation;
import br.com.cshub.taxify.validation.StockSurveyValidation.StatusChange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The stock credit survey of the firm (spec 2026-11-27, F5a): list with summary, detail, history and CSV (reads,
 * allowed in a blocked firm), "refresh now" with the atomic cooldown (CE-RN-09) and the contact status with optimistic
 * concurrency (CE-RN-10). The scope is the user's firm and, for non-ADMIN, their portfolio: a company outside answers
 * 404 {@code error.Company.not.found} (CE-RN-01). Off ({@code TAXIFY_STOCK_SURVEY_ENABLED=false}): every route answers
 * 404 {@code error.reform.stock.disabled} (CE-RN-15). From 01/07/2027 the writes answer 409 (CE-RN-12).
 */
@Service
public class StockSurveyService {

    private static final Logger log = LoggerFactory.getLogger(StockSurveyService.class);
    static final String DISABLED = "error.reform.stock.disabled";
    static final String NOT_FOUND = "error.reform.stock.survey.not.found";
    static final String COOLDOWN = "error.reform.stock.refresh.cooldown";
    static final String STATUS_CONFLICT = "error.reform.stock.status.conflict";
    static final String CLOSED = "error.reform.stock.survey.closed";
    static final String REFRESH_LOCK_PREFIX = "scs-refresh-";
    static final int REFRESH_LOCK_SECONDS = 15;
    static final String CALCULATION_FAILED = "error.reform.stock.calculation.failed";
    static final String COMPANY_NOT_FOUND = "error.Company.not.found";

    private final StockSurveyProperties properties;
    private final StockSurveyValidation validation;
    private final StockSurveyListRepository listRepository;
    private final StockSurveySourceRepository sourceRepository;
    private final StockCreditSurveyRepository surveyRepository;
    private final StockCreditSurveyEventRepository eventRepository;
    private final CompanyRepository companyRepository;
    private final StockSurveyCalculator calculator;
    private final StockSurveyMapper mapper;
    private final StockSurveyCsv csv;
    private final TenantAccessService tenantAccessService;
    private final CurrentUserService currentUserService;
    private final MessageService messageService;
    private final Clock clock;

    public StockSurveyService(StockSurveyProperties properties, StockSurveyValidation validation,
                              StockSurveyListRepository listRepository, StockSurveySourceRepository sourceRepository,
                              StockCreditSurveyRepository surveyRepository, StockCreditSurveyEventRepository eventRepository,
                              CompanyRepository companyRepository, StockSurveyCalculator calculator, StockSurveyMapper mapper,
                              StockSurveyCsv csv, TenantAccessService tenantAccessService,
                              CurrentUserService currentUserService, MessageService messageService, Clock clock) {
        this.properties = properties;
        this.validation = validation;
        this.listRepository = listRepository;
        this.sourceRepository = sourceRepository;
        this.surveyRepository = surveyRepository;
        this.eventRepository = eventRepository;
        this.companyRepository = companyRepository;
        this.calculator = calculator;
        this.mapper = mapper;
        this.csv = csv;
        this.tenantAccessService = tenantAccessService;
        this.currentUserService = currentUserService;
        this.messageService = messageService;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ reads

    /** Four queries whatever the size of the firm: summary, page, companies without a survey (and the user). */
    @Transactional(readOnly = true)
    public StockSurveyListDTO list(Integer year, List<String> classifications, List<String> statuses, List<String> coverages,
                                   String search, Integer page, Integer size, String sort) {
        assertEnabled();
        StockSurveyFilter filter = validation.checkFilter(year, classifications, statuses, coverages, search, page, size, sort);
        Scope scope = scope();
        StockSurveyListRepository.Summary summary = listRepository.summary(scope, filter);
        List<StockSurveyRowDTO> content = listRepository.page(scope, filter).stream().map(mapper::toRow).toList();
        long without = listRepository.withoutCalculation(scope, filter);
        int totalPages = (int) ((summary.companies() + filter.size() - 1) / filter.size());
        return new StockSurveyListDTO(mapper.notice(), summaryOf(summary, without),
                new StockSurveyListDTO.Page(content, summary.companies(), totalPages, filter.page(), filter.size()));
    }

    @Transactional(readOnly = true)
    public StockSurveyDetailDTO detail(UUID companyId, Integer year) {
        assertEnabled();
        int checkedYear = validation.checkYear(year);
        Company company = accessibleCompany(companyId);
        StockCreditSurvey survey = calculatedSurvey(companyId, checkedYear);
        return detailOf(survey, company);
    }

    @Transactional(readOnly = true)
    public List<StockSurveyEventDTO> history(UUID companyId, Integer year) {
        assertEnabled();
        int checkedYear = validation.checkYear(year);
        accessibleCompany(companyId);
        return surveyRepository.findOf(companyId, checkedYear)
                .map(survey -> eventRepository.history(survey.getId()).stream().map(mapper::toEvent).toList())
                .orElse(List.of());
    }

    /** Every row that passes the filters, in the list's order (paging ignored). */
    @Transactional(readOnly = true)
    public byte[] csv(Integer year, List<String> classifications, List<String> statuses, List<String> coverages,
                      String search, String sort) {
        assertEnabled();
        StockSurveyFilter filter = validation.checkFilter(year, classifications, statuses, coverages, search, 0,
                StockSurveyValidation.MAX_PAGE_SIZE, sort);
        List<StockSurveyRowDTO> rows = listRepository.all(scope(), filter).stream().map(mapper::toRow).toList();
        return csv.write(rows, mapper.notice());
    }

    // ------------------------------------------------------------------ writes

    /**
     * CE-RN-09: recalculates the company now (the routine's queries, batch of 1) and answers the detail. One
     * transaction, so the whole request uses the one connection of the request's EntityManager (JPA and JdbcTemplate
     * share it): never a second connection while holding the first. The cooldown is reserved atomically on the row:
     * a second click (another instance) waits for the row lock, then gets 409 with retryAt. A calculation that fails
     * (e.g. a regime in the history the code doesn't know) answers 409 {@code error.reform.stock.calculation.failed}
     * and rolls the reservation back - the cooldown is not consumed.
     */
    @Transactional
    public StockSurveyDetailDTO refresh(UUID companyId, Integer year) {
        assertEnabled();
        int checkedYear = validation.checkYear(year);
        Company company = accessibleCompany(companyId);
        LocalDateTime now = now();
        assertOpen(now);
        CompanyRef ref = sourceRepository.eligibleCompany(companyId).orElseThrow(this::companyNotFound);
        reserveRefresh(companyId, checkedYear, now);
        long started = System.nanoTime();
        calculateOrFail(ref);
        log.info("stock.survey.refresh companyId={} userId={} durationMs={}", companyId, currentUserId(),
                (System.nanoTime() - started) / 1_000_000);
        return detailOf(calculatedSurvey(companyId, checkedYear), company);
    }

    private void calculateOrFail(CompanyRef ref) {
        StockSurveyCalculator.BatchResult result;
        try {
            result = calculator.calculate(List.of(ref));
        } catch (RuntimeException e) {
            log.warn("stock.survey.company.failed companyId={} error={}", ref.companyId(), e.getClass().getSimpleName());
            throw calculationFailed();
        }
        if (result.failed() > 0) {
            throw calculationFailed();
        }
    }

    private ConflictException calculationFailed() {
        return new ConflictException(CALCULATION_FAILED, messageService.getMessage(CALCULATION_FAILED));
    }

    /** CE-RN-10: the row locked, the version the client read, one history event per change. */
    @Transactional
    public StockSurveyRowDTO changeStatus(UUID companyId, StockSurveyStatusChangeDTO body) {
        assertEnabled();
        StatusChange change = validation.checkStatusChange(body.year(), body.status(), body.note(), body.version());
        Company company = accessibleCompany(companyId);
        LocalDateTime now = now();
        assertOpen(now);
        StockCreditSurvey survey = surveyRepository.findForUpdate(companyId.toString(), change.year())
                .filter(StockCreditSurvey::isCalculated).orElseThrow(this::surveyNotFound);
        if (!survey.hasVersion(change.version())) {
            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put("currentVersion", survey.getVersion());
            throw new ConflictException(STATUS_CONFLICT, messageService.getMessage(STATUS_CONFLICT), extras);
        }
        String from = survey.getContactStatus().name();
        StockCreditSurveyEvent event = survey.changeContactStatus(change.status(), change.note(), currentLogin(), now);
        eventRepository.save(event);
        surveyRepository.flush();
        log.info("stock.survey.status.changed companyId={} userId={} from={} to={}", companyId, currentUserId(), from,
                change.status());
        return mapper.toRow(survey, company.getName(), cnpj(company));
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The company's refresh runs under a named lock of the MySQL session (released after the commit, NamedLocks): two
     * clicks on the same company - this instance or another - never insert the row at the same time (which InnoDB
     * answers with a deadlock); the second waits, then sees the first one's reservation and gets 409.
     */
    private void reserveRefresh(UUID companyId, int year, LocalDateTime now) {
        String name = NamedLocks.name(REFRESH_LOCK_PREFIX, companyId.toString());
        NamedLocks.Held lock = NamedLocks.untilTransactionEnds(() -> sourceRepository.getLock(name, REFRESH_LOCK_SECONDS),
                () -> releaseQuietly(name));
        if (lock == null) {
            throw cooldown(now.plusMinutes(properties.getRefreshCooldownMinutes()));
        }
        sourceRepository.ensureRow(companyId, year, now);
        LocalDateTime cooldownStart = now.minusMinutes(properties.getRefreshCooldownMinutes());
        if (sourceRepository.reserveRefresh(companyId, year, now, cooldownStart) == 0) {
            LocalDateTime retryAt = sourceRepository.refreshRequestedAtLocked(companyId, year)
                    .map(at -> at.plusMinutes(properties.getRefreshCooldownMinutes())).orElse(now);
            throw cooldown(retryAt);
        }
    }

    private ConflictException cooldown(LocalDateTime retryAt) {
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("retryAt", StockSurveyMapper.recife(retryAt));
        return new ConflictException(COOLDOWN, messageService.getMessage(COOLDOWN), extras);
    }

    private void releaseQuietly(String name) {
        try {
            sourceRepository.releaseLock(name);
        } catch (RuntimeException e) {
            // the MySQL session drops it anyway when the connection is closed
            log.warn("stock.survey.refresh.lock.release.failed error={}", e.getClass().getSimpleName());
        }
    }

    private StockSurveyDetailDTO detailOf(StockCreditSurvey survey, Company company) {
        LocalDateTime now = now();
        LocalDateTime availableAt = survey.getRefreshRequestedAt() == null ? null
                : survey.getRefreshRequestedAt().plusMinutes(properties.getRefreshCooldownMinutes());
        return mapper.toDetail(survey, company.getName(), cnpj(company),
                availableAt != null && availableAt.isAfter(now) ? availableAt : null, StockSurveyCalendar.isClosed(now));
    }

    private StockSurveyListDTO.Summary summaryOf(StockSurveyListRepository.Summary summary, long without) {
        Map<String, Long> byClassification = new LinkedHashMap<>();
        summary.byClassification().forEach((key, value) -> byClassification.put(key.name(), value));
        Map<String, Long> byStatus = new LinkedHashMap<>();
        summary.byContactStatus().forEach((key, value) -> byStatus.put(key.name(), value));
        return new StockSurveyListDTO.Summary(summary.companies(), without, byClassification, byStatus,
                summary.purchasesTotal().setScale(2, RoundingMode.HALF_UP), StockSurveyMapper.recife(summary.oldestCalculatedAt()));
    }

    private StockCreditSurvey calculatedSurvey(UUID companyId, int year) {
        return surveyRepository.findOf(companyId, year).filter(StockCreditSurvey::isCalculated).orElseThrow(this::surveyNotFound);
    }

    /** CE-RN-01: not deleted, not inactive, of the user's firm and (non-ADMIN) in their portfolio - else 404. */
    private Company accessibleCompany(UUID companyId) {
        Company company = companyRepository.findById(companyId)
                .filter(found -> !found.isDeleted() && !Boolean.FALSE.equals(found.getActive()))
                .orElseThrow(this::companyNotFound);
        tenantAccessService.assertCompanyAccess(company);
        return company;
    }

    private Scope scope() {
        User user = currentUserService.getCurrentUser()
                .filter(found -> found.getAccountingFirm() != null)
                .orElseThrow(() -> new ForbiddenException("error.access.denied", messageService.getMessage("error.access.denied")));
        return new Scope(user.getAccountingFirm().getId(), tenantAccessService.getRestrictedUserId().orElse(null));
    }

    private void assertEnabled() {
        if (!properties.isEnabled()) {
            throw new ResourceNotFoundException(DISABLED, messageService.getMessage(DISABLED));
        }
    }

    private void assertOpen(LocalDateTime now) {
        if (StockSurveyCalendar.isClosed(now)) {
            throw new ConflictException(CLOSED, messageService.getMessage(CLOSED));
        }
    }

    private static String cnpj(Company company) {
        return company.getDocument() == null ? null : company.getDocument().getNumber();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    private String currentLogin() {
        String login = currentUserService.getCurrentUser().map(User::getLogin).orElse("system");
        return login.length() > 50 ? login.substring(0, 50) : login;
    }

    private Object currentUserId() {
        return currentUserService.getCurrentUser().map(User::getId).orElse(null);
    }

    private ResourceNotFoundException companyNotFound() {
        return new ResourceNotFoundException(COMPANY_NOT_FOUND, messageService.getMessage(COMPANY_NOT_FOUND));
    }

    private ResourceNotFoundException surveyNotFound() {
        return new ResourceNotFoundException(NOT_FOUND, messageService.getMessage(NOT_FOUND));
    }
}
