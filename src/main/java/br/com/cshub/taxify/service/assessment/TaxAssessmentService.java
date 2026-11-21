package br.com.cshub.taxify.service.assessment;

import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.dto.assessment.TaxAssessmentDTO;
import br.com.cshub.taxify.enumerated.FiscalDirection;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.repository.FiscalXmlDocumentItemRepository;
import br.com.cshub.taxify.repository.FiscalXmlDocumentRepository;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * and hands them to {@link TaxAssessmentCalculator}.
 */
@Service
public class TaxAssessmentService {

    private final CompanyValidation companyValidation;
    private final CompanyRepository companyRepository;
    private final FiscalXmlDocumentItemRepository itemRepository;
    private final FiscalXmlDocumentRepository documentRepository;
    private final TenantAccessService tenantAccessService;
    private final MessageService messageService;

    public TaxAssessmentService(CompanyValidation companyValidation, CompanyRepository companyRepository,
                                FiscalXmlDocumentItemRepository itemRepository, FiscalXmlDocumentRepository documentRepository,
                                TenantAccessService tenantAccessService, MessageService messageService) {
        this.companyValidation = companyValidation;
        this.companyRepository = companyRepository;
        this.itemRepository = itemRepository;
        this.documentRepository = documentRepository;
        this.tenantAccessService = tenantAccessService;
        this.messageService = messageService;
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
        List<Company> companies = companyRepository.findByAccountingFirm_IdAndDeletedIsFalseOrderByNameAsc(firmId).stream()
                .filter(company -> !Boolean.FALSE.equals(company.getActive()))
                .filter(company -> tenantAccessService.canAccessCompany(company.getId()))
                .toList();
        return companies.isEmpty() ? List.of() : assessAll(companies, period, false);
    }

    private List<TaxAssessmentDTO> assessAll(List<Company> companies, YearMonth month, boolean withHistory) {
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

        return companies.stream()
                .map(company -> TaxAssessmentCalculator.assess(new TaxAssessmentCalculator.Input(
                        company, month,
                        revenue.getOrDefault(company.getId(), Map.of()),
                        invoices.getOrDefault(company.getId(), 0L),
                        firstSale.get(company.getId()),
                        withHistory)))
                .toList();
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

    /** "2026-10"; blank = the previous month (the one usually being closed). */
    private YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) {
            return YearMonth.now().minusMonths(1);
        }
        try {
            return YearMonth.parse(month.strip());
        } catch (DateTimeParseException e) {
            throw new BadRequestException(messageService.getMessage("error.assessment.month.invalid"));
        }
    }
}
