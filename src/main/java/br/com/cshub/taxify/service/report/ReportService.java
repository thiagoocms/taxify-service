package br.com.cshub.taxify.service.report;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.dto.assessment.MonthRevenueDTO;
import br.com.cshub.taxify.dto.assessment.TaxAssessmentDTO;
import br.com.cshub.taxify.dto.assessment.TaxEstimateDTO;
import br.com.cshub.taxify.dto.report.ReportDTOs;
import br.com.cshub.taxify.enumerated.FiscalDirection;
import br.com.cshub.taxify.enumerated.PendencySeverity;
import br.com.cshub.taxify.enumerated.PendencyStatus;
import br.com.cshub.taxify.enumerated.PendencyType;
import br.com.cshub.taxify.enumerated.TaxRegime;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.repository.FiscalXmlDocumentItemRepository;
import br.com.cshub.taxify.repository.FiscalXmlDocumentRepository;
import br.com.cshub.taxify.repository.PendencyRepository;
import br.com.cshub.taxify.service.assessment.TaxAssessmentService;
import br.com.cshub.taxify.service.retention.RetentionCalculator;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reports and simulation (roadmap M12): the monthly report of a company (what happened in the month, for the
 * client), the purchases' curva ABC and the simulation of Simples x Presumido x Real over the last 12 months
 * with the real numbers of the notes - always estimates, with the premises shown.
 */
@Service
public class ReportService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    /** Curva ABC: A up to 80% of the value, B up to 95%, C the rest. */
    private static final BigDecimal CURVE_A = BigDecimal.valueOf(80);
    private static final BigDecimal CURVE_B = BigDecimal.valueOf(95);
    private static final int MAX_ABC_ITEMS = 300;
    static final BigDecimal DEFAULT_MARGIN = BigDecimal.valueOf(10);
    static final BigDecimal DEFAULT_ISS = BigDecimal.valueOf(5);
    /** IRPJ additional: 10% over R$ 240 thousand of profit a year. */
    private static final BigDecimal IRPJ_ADDITIONAL_LIMIT = BigDecimal.valueOf(240_000);

    private final TaxAssessmentService assessmentService;
    private final FiscalXmlDocumentRepository documentRepository;
    private final FiscalXmlDocumentItemRepository itemRepository;
    private final PendencyRepository pendencyRepository;
    private final RetentionCalculator retentionCalculator;
    private final CompanyValidation companyValidation;
    private final MessageService messageService;

    public ReportService(TaxAssessmentService assessmentService, FiscalXmlDocumentRepository documentRepository,
                         FiscalXmlDocumentItemRepository itemRepository, PendencyRepository pendencyRepository,
                         RetentionCalculator retentionCalculator, CompanyValidation companyValidation,
                         MessageService messageService) {
        this.assessmentService = assessmentService;
        this.documentRepository = documentRepository;
        this.itemRepository = itemRepository;
        this.pendencyRepository = pendencyRepository;
        this.retentionCalculator = retentionCalculator;
        this.companyValidation = companyValidation;
        this.messageService = messageService;
    }

    // ---------------------------------------------------------------- monthly report

    @Transactional(readOnly = true)
    public ReportDTOs.Monthly monthly(String companyId, String competence) {
        Company company = company(companyId);
        YearMonth month = month(competence);
        TaxAssessmentDTO assessment = assessmentService.assess(company.getId(), month.toString());
        LocalDateTime from = month.atDay(1).atStartOfDay();
        LocalDateTime to = month.plusMonths(1).atDay(1).atStartOfDay();

        ReportDTOs.DirectionTotals entries = emptyTotals();
        ReportDTOs.DirectionTotals exits = emptyTotals();
        for (Object[] row : documentRepository.totalsByDirection(company.getId(), from, to.minusNanos(1))) {
            ReportDTOs.DirectionTotals totals = new ReportDTOs.DirectionTotals(number(row[1]), decimal(row[2]), decimal(row[3]),
                    decimal(row[4]), decimal(row[5]), decimal(row[6]), decimal(row[7]), number(row[8]));
            if (row[0] == FiscalDirection.INBOUND) {
                entries = totals;
            } else if (row[0] == FiscalDirection.OUTBOUND) {
                exits = totals;
            }
        }
        List<ReportDTOs.CfopTotal> cfops = new ArrayList<>();
        for (Object[] row : itemRepository.sumByCompanyMonthAndCfop(List.of(company.getId()), from, to)) {
            cfops.add(new ReportDTOs.CfopTotal(row[3] == FiscalDirection.INBOUND ? "ENTRADA" : "SAIDA",
                    row[4] == null ? "-" : row[4].toString(), decimal(row[5])));
        }
        cfops.sort((a, b) -> a.direction().equals(b.direction()) ? b.total().compareTo(a.total()) : a.direction().compareTo(b.direction()));

        Map<RetentionCalculator.Nature, BigDecimal> retentionTotals = new EnumMap<>(RetentionCalculator.Nature.class);
        for (RetentionCalculator.Retention retention : retentionCalculator.compute(List.of(company), month).retentions()) {
            retentionTotals.merge(retention.nature(), retention.amount(), BigDecimal::add);
        }
        List<ReportDTOs.RetentionTotal> retentions = retentionTotals.entrySet().stream()
                .map(entry -> new ReportDTOs.RetentionTotal(entry.getKey().name(), entry.getValue())).toList();

        Map<PendencySeverity, Long> bySeverity = new EnumMap<>(PendencySeverity.class);
        for (Object[] row : pendencyRepository.countOpen(List.of(company.getId()))) {
            bySeverity.merge((PendencySeverity) row[1], (Long) row[2], Long::sum);
        }
        List<ReportDTOs.PendencyCount> pendencies = bySeverity.entrySet().stream()
                .map(entry -> new ReportDTOs.PendencyCount(entry.getKey().name(), entry.getValue())).toList();
        long auditFindings = pendencyRepository.findByCompany_IdAndTypeAndCompetence(company.getId(), PendencyType.AUDIT_FINDING,
                month.toString()).stream().filter(pendency -> pendency.getStatus() == PendencyStatus.ABERTA).count();

        return new ReportDTOs.Monthly(company.getId(), company.getName(),
                company.getDocument() == null ? null : company.getDocument().getNumber(), month.toString(),
                assessment.taxRegimeLabel(), assessment.revenue(), assessment.rbt12(), assessment.invoicesIssued(),
                assessment.taxes(), assessment.totalTaxes(), entries, exits, cfops, retentions, pendencies, auditFindings,
                assessment.history(), assessment.alerts());
    }

    // ---------------------------------------------------------------- curva ABC

    /**
     * Purchases of the {@code months} months ending at {@code competence}, by product or supplier, ranked by
     * value: A up to 80% of the total, B up to 95%, C the rest.
     */
    @Transactional(readOnly = true)
    public ReportDTOs.Abc abc(String companyId, String competence, String by, Integer months) {
        Company company = company(companyId);
        YearMonth end = month(competence);
        int span = months == null ? 12 : Math.max(1, Math.min(months, 24));
        YearMonth start = end.minusMonths(span - 1L);
        LocalDateTime from = start.atDay(1).atStartOfDay();
        LocalDateTime to = end.plusMonths(1).atDay(1).atStartOfDay();
        boolean bySupplier = "FORNECEDOR".equalsIgnoreCase(by);
        List<Object[]> rows = bySupplier ? documentRepository.sumPurchasesBySupplier(company.getId(), from, to)
                : itemRepository.sumPurchasesByProduct(company.getId(), from, to);
        return abc(bySupplier ? "FORNECEDOR" : "PRODUTO", start, end, rows, bySupplier);
    }

    static ReportDTOs.Abc abc(String by, YearMonth start, YearMonth end, List<Object[]> rows, boolean bySupplier) {
        BigDecimal total = rows.stream().map(row -> decimal(bySupplier ? row[2] : row[3])).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<ReportDTOs.AbcItem> items = new ArrayList<>();
        long countA = 0;
        long countB = 0;
        long countC = 0;
        BigDecimal accumulated = BigDecimal.ZERO;
        for (Object[] row : rows) {
            BigDecimal value = decimal(bySupplier ? row[2] : row[3]);
            if (value.signum() <= 0) {
                continue;
            }
            BigDecimal before = accumulated;
            BigDecimal share = total.signum() == 0 ? BigDecimal.ZERO : value.multiply(HUNDRED).divide(total, 2, RoundingMode.HALF_UP);
            accumulated = accumulated.add(share);
            // the item that crosses a limit still belongs to the class it started in
            String curve = before.compareTo(CURVE_A) < 0 ? "A" : before.compareTo(CURVE_B) < 0 ? "B" : "C";
            switch (curve) {
                case "A" -> countA++;
                case "B" -> countB++;
                default -> countC++;
            }
            if (items.size() < MAX_ABC_ITEMS) {
                items.add(bySupplier
                        ? new ReportDTOs.AbcItem(text(row[0]), text(row[1]), null, value, number(row[3]), share,
                        accumulated.min(HUNDRED), curve)
                        : new ReportDTOs.AbcItem(text(row[0]), text(row[1]), row[2] == null ? null : "NCM " + row[2], value,
                        number(row[4]), share, accumulated.min(HUNDRED), curve));
            }
        }
        return new ReportDTOs.Abc(by, start.toString(), end.toString(), total, countA, countB, countC, items);
    }

    // ---------------------------------------------------------------- simulation

    /**
     * Simples x Presumido x Real over the 12 months ending at {@code competence}, with the assessment's own rules
     * and the notes' real numbers. Outside the Simples, ISS (premise rate on services) and the ICMS balance of the
     * notes (exits minus entries) are added; the Real also needs a profit margin (premise) for IRPJ/CSLL.
     */
    @Transactional(readOnly = true)
    public ReportDTOs.Simulation simulate(String companyId, String competence, BigDecimal profitMargin, BigDecimal issRate) {
        Company company = company(companyId);
        YearMonth end = month(competence);
        YearMonth start = end.minusMonths(11);
        BigDecimal margin = profitMargin == null ? DEFAULT_MARGIN : profitMargin.max(BigDecimal.ZERO).min(HUNDRED);
        BigDecimal iss = issRate == null ? DEFAULT_ISS : issRate.max(BigDecimal.ZERO).min(BigDecimal.valueOf(5));

        Map<TaxRegime, List<TaxAssessmentDTO>> results = assessmentService.simulate(company, end,
                List.of(TaxRegime.SIMPLES_NACIONAL, TaxRegime.LUCRO_PRESUMIDO, TaxRegime.LUCRO_REAL));
        List<TaxAssessmentDTO> base = results.get(TaxRegime.SIMPLES_NACIONAL);
        BigDecimal revenue = sum(base.stream().map(dto -> dto.revenue() == null ? null : dto.revenue().net()).toList());
        BigDecimal services = sum(base.stream().map(dto -> dto.revenue() == null ? null : dto.revenue().services()).toList());
        BigDecimal icmsBalance = icmsBalance(company.getId(), start, end);
        String currentRegime = assessmentService.assess(company.getId(), end.toString()).taxRegimeLabel();

        List<ReportDTOs.RegimeResult> regimes = new ArrayList<>();
        for (Map.Entry<TaxRegime, List<TaxAssessmentDTO>> entry : results.entrySet()) {
            regimes.add(regime(entry.getKey(), entry.getValue(), revenue, services, icmsBalance, margin, iss));
        }
        BigDecimal cheapest = regimes.stream().map(ReportDTOs.RegimeResult::total).filter(total -> total != null)
                .min(BigDecimal::compareTo).orElse(null);
        regimes = regimes.stream().map(result -> new ReportDTOs.RegimeResult(result.regime(), result.label(), result.total(),
                result.effectiveRate(), result.taxes(), result.notes(),
                cheapest != null && revenue.signum() > 0 && cheapest.compareTo(result.total()) == 0)).toList();

        List<String> premises = List.of(
                "Receita: as notas de saída dos 12 meses (canceladas fora), como na Apuração.",
                "Simples Nacional: anexos pela atividade e pelo anexo de serviço do cadastro (padrão III); a folha (fator R) não é considerada.",
                "Fora do Simples: ISS de " + iss.stripTrailingZeros().toPlainString() + "% sobre os serviços e o saldo de ICMS destacado nas notas (saídas menos entradas).",
                "Lucro Real: lucro de " + margin.stripTrailingZeros().toPlainString() + "% da receita para IRPJ e CSLL; PIS/COFINS não cumulativos com crédito sobre as compras.",
                "Estimativa pelas notas: não considera folha, receitas sem nota, ST/monofásico nem benefícios fiscais - confira antes de decidir.");
        return new ReportDTOs.Simulation(company.getName(), currentRegime, start.toString(), end.toString(), revenue, margin, iss,
                regimes, premises);
    }

    static ReportDTOs.RegimeResult regime(TaxRegime regime, List<TaxAssessmentDTO> months, BigDecimal revenue, BigDecimal services,
                                          BigDecimal icmsBalance, BigDecimal margin, BigDecimal iss) {
        Map<String, ReportDTOs.TaxLine> lines = new LinkedHashMap<>();
        for (TaxAssessmentDTO month : months) {
            for (TaxEstimateDTO tax : month.taxes() == null ? List.<TaxEstimateDTO>of() : month.taxes()) {
                if (tax.amount() == null) {
                    continue;
                }
                String code = tax.code().startsWith("DAS") ? "DAS" : tax.code();
                String label = tax.code().startsWith("DAS") ? "DAS (Simples Nacional)" : tax.label();
                lines.merge(code, new ReportDTOs.TaxLine(code, label, tax.amount(), null),
                        (a, b) -> new ReportDTOs.TaxLine(a.code(), a.label(), a.amount().add(b.amount()), null));
            }
        }
        List<String> notes = new ArrayList<>();
        if (regime != TaxRegime.SIMPLES_NACIONAL) {
            BigDecimal issValue = percent(services, iss);
            if (issValue.signum() > 0) {
                lines.put("ISS", new ReportDTOs.TaxLine("ISS", "ISS (" + iss.stripTrailingZeros().toPlainString() + "% dos serviços)", issValue, null));
            }
            if (icmsBalance.signum() > 0) {
                lines.put("ICMS", new ReportDTOs.TaxLine("ICMS", "ICMS (saldo das notas)", icmsBalance, null));
            }
        } else {
            notes.add("O DAS já inclui ICMS e ISS (até o sublimite de R$ 3,6 milhões).");
        }
        if (regime == TaxRegime.LUCRO_REAL) {
            BigDecimal profit = percent(revenue, margin);
            BigDecimal irpj = percent(profit, BigDecimal.valueOf(15))
                    .add(percent(profit.subtract(IRPJ_ADDITIONAL_LIMIT).max(BigDecimal.ZERO), BigDecimal.TEN));
            lines.put("IRPJ", new ReportDTOs.TaxLine("IRPJ", "IRPJ (15% + adicional sobre o lucro estimado)", irpj, null));
            lines.put("CSLL", new ReportDTOs.TaxLine("CSLL", "CSLL (9% sobre o lucro estimado)", percent(profit, BigDecimal.valueOf(9)), null));
            notes.add("IRPJ e CSLL sobre um lucro de " + margin.stripTrailingZeros().toPlainString() + "% da receita (premissa).");
        }
        if (regime == TaxRegime.LUCRO_PRESUMIDO) {
            notes.add("IRPJ/CSLL pela presunção (8%/12% nas vendas, 32% nos serviços); PIS/COFINS cumulativos.");
        }
        BigDecimal total = lines.values().stream().map(ReportDTOs.TaxLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal rate = revenue.signum() == 0 ? BigDecimal.ZERO : total.multiply(HUNDRED).divide(revenue, 2, RoundingMode.HALF_UP);
        return new ReportDTOs.RegimeResult(regime.name(), label(regime), total, rate, List.copyOf(lines.values()), notes, false);
    }

    /** ICMS highlighted on the exits minus the entries' (credit), over the period; never negative. */
    private BigDecimal icmsBalance(UUID companyId, YearMonth start, YearMonth end) {
        BigDecimal exits = BigDecimal.ZERO;
        BigDecimal entries = BigDecimal.ZERO;
        for (Object[] row : documentRepository.totalsByDirection(companyId, start.atDay(1).atStartOfDay(),
                end.plusMonths(1).atDay(1).atStartOfDay().minusNanos(1))) {
            if (row[0] == FiscalDirection.OUTBOUND) {
                exits = exits.add(decimal(row[3]));
            } else if (row[0] == FiscalDirection.INBOUND) {
                entries = entries.add(decimal(row[3]));
            }
        }
        return exits.subtract(entries).max(BigDecimal.ZERO);
    }

    // ---------------------------------------------------------------- helpers

    static String label(TaxRegime regime) {
        return switch (regime) {
            case SIMPLES_NACIONAL -> "Simples Nacional";
            case LUCRO_PRESUMIDO -> "Lucro Presumido";
            case LUCRO_REAL -> "Lucro Real";
            case MEI -> "MEI";
            default -> regime.name();
        };
    }

    private Company company(String companyId) {
        try {
            return companyValidation.checkExist(UUID.fromString(companyId.strip()));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BadRequestException("error.generic.bad.request", messageService.getMessage("error.generic.bad.request"));
        }
    }

    static YearMonth month(String competence) {
        if (competence == null || competence.isBlank()) {
            return YearMonth.now().minusMonths(1);
        }
        try {
            return YearMonth.parse(competence.strip());
        } catch (DateTimeParseException e) {
            throw new BadRequestException("Competência inválida: use AAAA-MM.");
        }
    }

    private static ReportDTOs.DirectionTotals emptyTotals() {
        return new ReportDTOs.DirectionTotals(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, 0);
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().filter(value -> value != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal percent(BigDecimal value, BigDecimal rate) {
        return value.multiply(rate).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    static BigDecimal decimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
    }

    private static long number(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    /** The months of the history, for the PDF. */
    static List<MonthRevenueDTO> history(ReportDTOs.Monthly report) {
        return report.history() == null ? List.of() : report.history();
    }
}
