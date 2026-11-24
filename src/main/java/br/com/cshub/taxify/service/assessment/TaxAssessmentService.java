package br.com.cshub.taxify.service.assessment;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.domain.assessment.FiscalPeriodClosing;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.dto.assessment.IbsCbsSummaryDTO;
import br.com.cshub.taxify.dto.assessment.PeriodClosingDTO;
import br.com.cshub.taxify.dto.assessment.TaxAssessmentDTO;
import br.com.cshub.taxify.enumerated.FiscalDirection;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.repository.FiscalPeriodClosingRepository;
import br.com.cshub.taxify.repository.FiscalXmlDocumentItemRepository;
import br.com.cshub.taxify.repository.FiscalXmlDocumentRepository;
import br.com.cshub.taxify.service.CompanyTaxRegimeService;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Apuração do período: loads the notes' item values of the period and the 12 months before
 * (grouped queries, for one company or the whole firm at once), classifies them by CFOP
 * and hands them to {@link TaxAssessmentCalculator}. A closed month is answered from the
 * snapshot taken when it was closed (FiscalPeriodClosingService).
 */
@Service
public class TaxAssessmentService {

    private static final Logger log = LoggerFactory.getLogger(TaxAssessmentService.class);

    private final CompanyValidation companyValidation;
    private final CompanyRepository companyRepository;
    private final FiscalXmlDocumentItemRepository itemRepository;
    private final FiscalXmlDocumentRepository documentRepository;
    private final FiscalPeriodClosingRepository closingRepository;
    private final TenantAccessService tenantAccessService;
    private final MessageService messageService;
    private final CompanyTaxRegimeService taxRegimeService;
    private final ObjectMapper objectMapper;
    private final TaxAssessmentCache cache;

    public TaxAssessmentService(CompanyValidation companyValidation, CompanyRepository companyRepository,
                                FiscalXmlDocumentItemRepository itemRepository, FiscalXmlDocumentRepository documentRepository,
                                FiscalPeriodClosingRepository closingRepository, TenantAccessService tenantAccessService,
                                MessageService messageService, CompanyTaxRegimeService taxRegimeService, ObjectMapper objectMapper,
                                TaxAssessmentCache cache) {
        this.cache = cache;
        this.companyValidation = companyValidation;
        this.companyRepository = companyRepository;
        this.itemRepository = itemRepository;
        this.documentRepository = documentRepository;
        this.closingRepository = closingRepository;
        this.tenantAccessService = tenantAccessService;
        this.messageService = messageService;
        this.taxRegimeService = taxRegimeService;
        this.objectMapper = objectMapper;
    }

    /** One company, with its 13-month history. */
    @Transactional(readOnly = true)
    public TaxAssessmentDTO assess(UUID companyId, String month) {
        Company company = companyValidation.checkExist(companyId);
        return assessAll(List.of(company), parseMonth(month), true).getFirst();
    }

    /** Every active company of the current user's firm (only their portfolio for non-admins), without history. */
    @Transactional(readOnly = true)
    public List<TaxAssessmentDTO> assessFirm(String month) {
        UUID firmId = tenantAccessService.getCurrentAccountingFirmId()
                .orElseThrow(() -> new BadRequestException(messageService.getMessage("error.assessment.firm.required")));
        YearMonth period = parseMonth(month);
        List<Company> companies = tenantAccessService.filterAccessible(
                companyRepository.findByAccountingFirm_IdAndDeletedIsFalseOrderByNameAsc(firmId).stream()
                        .filter(company -> !Boolean.FALSE.equals(company.getActive()))
                        .toList(),
                Company::getId);
        return companies.isEmpty() ? List.of() : assessAll(companies, period, false);
    }

    /**
     * The same assessment rules run under another regime, for the 12 months ending at {@code month}
     * (the regimes simulation of the reports). Closings are ignored - it's a what-if.
     *
     * @return regime -> the 12 monthly assessments, oldest first
     */
    @Transactional(readOnly = true)
    public Map<br.com.cshub.taxify.enumerated.TaxRegime, List<TaxAssessmentDTO>> simulate(Company company, YearMonth month,
                                                                                          List<br.com.cshub.taxify.enumerated.TaxRegime> regimes) {
        List<UUID> ids = List.of(company.getId());
        Map<YearMonth, TaxAssessmentCalculator.MonthRevenue> revenue = revenueByCompany(ids,
                month.minusMonths(23).atDay(1).atStartOfDay(), month.plusMonths(1).atDay(1).atStartOfDay())
                .getOrDefault(company.getId(), Map.of());
        YearMonth firstSale = null;
        for (Object[] row : documentRepository.firstOutboundByCompany(ids)) {
            if (row[1] != null) {
                firstSale = YearMonth.from((LocalDateTime) row[1]);
            }
        }
        YearMonth activityStart = company.getOpeningDate() != null ? YearMonth.from(company.getOpeningDate()) : firstSale;
        CompanyTaxRegimeService.RegimeAt current = taxRegimeService.regimesAt(List.of(company), month).get(company.getId());
        Map<br.com.cshub.taxify.enumerated.TaxRegime, List<TaxAssessmentDTO>> result = new java.util.LinkedHashMap<>();
        for (br.com.cshub.taxify.enumerated.TaxRegime regime : regimes) {
            CompanyTaxRegimeService.RegimeAt simulated = new CompanyTaxRegimeService.RegimeAt(regime,
                    current == null ? null : current.activity(), current == null ? null : current.serviceAnnex(), regime.name());
            List<TaxAssessmentDTO> months = new java.util.ArrayList<>();
            for (YearMonth m = month.minusMonths(11); !m.isAfter(month); m = m.plusMonths(1)) {
                months.add(TaxAssessmentCalculator.assess(new TaxAssessmentCalculator.Input(company, m, simulated, revenue, 1,
                        activityStart, false)));
            }
            result.put(regime, months);
        }
        return result;
    }

    /** The month recalculated from the notes, ignoring any closing - what a closing freezes. */
    TaxAssessmentDTO calculate(Company company, YearMonth month) {
        return calculateAll(List.of(company), month, true).getFirst();
    }

    String toJson(TaxAssessmentDTO assessment) {
        return objectMapper.writeValueAsString(assessment);
    }

    /** "2026-10"; blank = the previous month (the one usually being closed). */
    YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) {
            return YearMonth.now().minusMonths(1);
        }
        try {
            return YearMonth.parse(month.strip());
        } catch (DateTimeParseException e) {
            throw new BadRequestException(messageService.getMessage("error.assessment.month.invalid"));
        }
    }

    // ------------------------------------------------------------------ internals

    private List<TaxAssessmentDTO> assessAll(List<Company> companies, YearMonth month, boolean withHistory) {
        List<UUID> ids = companies.stream().map(Company::getId).toList();
        Map<UUID, FiscalPeriodClosing> closings = new HashMap<>();
        for (FiscalPeriodClosing closing : closingRepository.findByCompany_IdInAndPeriodAndDeletedIsFalse(ids, month.atDay(1))) {
            closings.put(closing.getCompany().getId(), closing);
        }
        Map<UUID, Long> pending = new HashMap<>();
        for (Object[] row : documentRepository.countPendingReviewByCompany(ids, month.atDay(1).atStartOfDay(),
                month.plusMonths(1).atDay(1).atStartOfDay())) {
            pending.put((UUID) row[0], ((Number) row[1]).longValue());
        }

        // closed months come from their snapshot; only the others are recalculated
        Map<UUID, TaxAssessmentDTO> frozen = new HashMap<>();
        for (FiscalPeriodClosing closing : closings.values()) {
            if (closing.isClosed() && closing.getSnapshot() != null) {
                TaxAssessmentDTO snapshot = fromJson(closing.getSnapshot());
                if (snapshot != null) {
                    frozen.put(closing.getCompany().getId(), snapshot);
                }
            }
        }
        List<Company> toCalculate = companies.stream().filter(company -> !frozen.containsKey(company.getId())).toList();
        Map<UUID, TaxAssessmentDTO> calculated = withHistory ? new HashMap<>() : cached(toCalculate, month);
        List<Company> missing = toCalculate.stream().filter(company -> !calculated.containsKey(company.getId())).toList();
        if (!missing.isEmpty()) {
            // versions read before the queries: a note arriving meanwhile keeps the result out of the cache
            Map<UUID, Long> versions = new HashMap<>();
            missing.forEach(company -> versions.put(company.getId(), cache.version(company.getId())));
            for (TaxAssessmentDTO dto : calculateAll(missing, month, withHistory)) {
                UUID id = UUID.fromString(dto.companyId());
                calculated.put(id, dto);
                if (!withHistory) {
                    cache.put(id, month, dto, versions.get(id));
                }
            }
        }

        return companies.stream().map(company -> {
            UUID id = company.getId();
            TaxAssessmentDTO base = frozen.containsKey(id) ? frozen.get(id) : calculated.get(id);
            return base.withClosing(toClosingDto(closings.get(id), pending.getOrDefault(id, 0L)), withHistory);
        }).toList();
    }

    /** The firm-wide rows (no history) already calculated for the month - see {@link TaxAssessmentCache}. */
    private Map<UUID, TaxAssessmentDTO> cached(List<Company> companies, YearMonth month) {
        Map<UUID, TaxAssessmentDTO> found = new HashMap<>();
        for (Company company : companies) {
            cache.get(company.getId(), month).ifPresent(dto -> found.put(company.getId(), dto));
        }
        return found;
    }

    private List<TaxAssessmentDTO> calculateAll(List<Company> companies, YearMonth month, boolean withHistory) {
        List<UUID> ids = companies.stream().map(Company::getId).toList();
        LocalDateTime from = month.minusMonths(12).atDay(1).atStartOfDay();
        LocalDateTime to = month.plusMonths(1).atDay(1).atStartOfDay();

        Map<UUID, Map<YearMonth, TaxAssessmentCalculator.MonthRevenue>> revenue = revenueByCompany(ids, from, to);
        Map<UUID, Long> invoices = new HashMap<>();
        for (Object[] row : documentRepository.countOutboundByCompany(ids, month.atDay(1).atStartOfDay(), to)) {
            invoices.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        Map<UUID, YearMonth> firstSale = new HashMap<>();
        for (Object[] row : documentRepository.firstOutboundByCompany(ids)) {
            if (row[1] != null) {
                firstSale.put((UUID) row[0], YearMonth.from((LocalDateTime) row[1]));
            }
        }
        Map<UUID, CompanyTaxRegimeService.RegimeAt> regimes = taxRegimeService.regimesAt(companies, month);
        Map<UUID, IbsCbsSummaryDTO> ibsCbs = ibsCbsByCompany(ids, month);

        return companies.stream()
                .map(company -> TaxAssessmentCalculator.assess(new TaxAssessmentCalculator.Input(
                        company, month,
                        regimes.get(company.getId()),
                        revenue.getOrDefault(company.getId(), Map.of()),
                        invoices.getOrDefault(company.getId(), 0L),
                        company.getOpeningDate() != null ? YearMonth.from(company.getOpeningDate()) : firstSale.get(company.getId()),
                        withHistory)).withIbsCbs(ibsCbs.get(company.getId())))
                .toList();
    }

    /** The informative IBS/CBS block of each company that has notes with the group in the month. */
    private Map<UUID, IbsCbsSummaryDTO> ibsCbsByCompany(Collection<UUID> ids, YearMonth month) {
        Map<UUID, Object[]> outbound = new HashMap<>();
        Map<UUID, Object[]> inbound = new HashMap<>();
        for (Object[] row : itemRepository.sumIbsCbsByCompanyAndDirection(ids, month.atDay(1).atStartOfDay(),
                month.plusMonths(1).atDay(1).atStartOfDay())) {
            (row[1] == FiscalDirection.OUTBOUND ? outbound : inbound).put((UUID) row[0], row);
        }
        Map<UUID, IbsCbsSummaryDTO> result = new HashMap<>();
        for (UUID id : ids) {
            Object[] out = outbound.get(id);
            Object[] in = inbound.get(id);
            if (out == null && in == null) {
                continue;
            }
            BigDecimal debitIbs = amount(out, 2);
            BigDecimal debitCbs = amount(out, 3);
            BigDecimal creditIbs = amount(in, 2);
            BigDecimal creditCbs = amount(in, 3);
            result.put(id, new IbsCbsSummaryDTO(debitIbs, debitCbs, creditIbs, creditCbs,
                    debitIbs.subtract(creditIbs), debitCbs.subtract(creditCbs),
                    count(out), count(in), IBS_CBS_NOTE));
        }
        return result;
    }

    private static final String IBS_CBS_NOTE = "Informativo: valores destacados nas notas. Em 2026 (ano de teste) o IBS/CBS"
            + " é apenas informado, sem recolhimento; a CBS passa a ser devida em 2027.";

    private static BigDecimal amount(Object[] row, int index) {
        if (row == null || row[index] == null) {
            return BigDecimal.ZERO.setScale(2);
        }
        BigDecimal value = row[index] instanceof BigDecimal decimal ? decimal : new BigDecimal(row[index].toString());
        return value.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static long count(Object[] row) {
        return row == null ? 0 : ((Number) row[4]).longValue();
    }

    private Map<UUID, Map<YearMonth, TaxAssessmentCalculator.MonthRevenue>> revenueByCompany(Collection<UUID> ids,
                                                                                              LocalDateTime from, LocalDateTime to) {
        Map<UUID, Map<YearMonth, TaxAssessmentCalculator.MonthRevenue>> result = new HashMap<>();
        for (Object[] row : itemRepository.sumByCompanyMonthAndCfop(ids, from, to)) {
            RevenueCfops.Kind kind = RevenueCfops.classify((FiscalDirection) row[3], (String) row[4]);
            if (kind == RevenueCfops.Kind.NONE) {
                continue;
            }
            YearMonth month = YearMonth.of(((Number) row[1]).intValue(), ((Number) row[2]).intValue());
            BigDecimal value = row[5] instanceof BigDecimal decimal ? decimal : new BigDecimal(Objects.toString(row[5], "0"));
            result.computeIfAbsent((UUID) row[0], key -> new HashMap<>())
                    .merge(month, TaxAssessmentCalculator.MonthRevenue.EMPTY.plus(kind, value), TaxAssessmentCalculator.MonthRevenue::add);
        }
        return result;
    }

    static PeriodClosingDTO toClosingDto(FiscalPeriodClosing closing, long pendingReviews) {
        if (closing == null) {
            return PeriodClosingDTO.open(pendingReviews);
        }
        return new PeriodClosingDTO(closing.getStatus().name(), closing.getClosedAt(), closing.getClosedBy(),
                closing.isGuideIssued(), closing.getGuideAmount(), closing.getGuideDueDate(), closing.getNotes(), pendingReviews);
    }

    private TaxAssessmentDTO fromJson(String json) {
        try {
            return objectMapper.readValue(json, TaxAssessmentDTO.class);
        } catch (JacksonException e) {
            // an unreadable snapshot (older format) falls back to recalculating
            log.warn("Unreadable closing snapshot - recalculating the month", e);
            return null;
        }
    }
}
