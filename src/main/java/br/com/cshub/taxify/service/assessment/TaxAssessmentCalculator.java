package br.com.cshub.taxify.service.assessment;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.dto.assessment.AssessmentAlertDTO;
import br.com.cshub.taxify.dto.assessment.AssessmentAlertDTO.Level;
import br.com.cshub.taxify.dto.assessment.MonthRevenueDTO;
import br.com.cshub.taxify.dto.assessment.TaxAssessmentDTO;
import br.com.cshub.taxify.dto.assessment.TaxEstimateDTO;
import br.com.cshub.taxify.enumerated.SimplesAnnex;
import br.com.cshub.taxify.enumerated.TaxRegime;
import br.com.cshub.taxify.service.CompanyTaxRegimeService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The assessment rules, with no I/O: from a company's monthly revenue (already classified
 * by {@link RevenueCfops}) to RBT12, estimated taxes and alerts. Estimates only - what the
 * notes show; payroll (fator R), ST/monofásico segregation and non-invoiced revenue are
 * not in it.
 */
public final class TaxAssessmentCalculator {

    static final BigDecimal SIMPLES_SUBLIMIT = new BigDecimal("3600000");
    static final BigDecimal SIMPLES_CEILING = new BigDecimal("4800000");
    static final BigDecimal MEI_LIMIT = new BigDecimal("81000");
    /** Up to 20% over the MEI limit the excess is taxed; beyond it the exclusion is retroactive. */
    static final BigDecimal MEI_TOLERANCE = new BigDecimal("97200");
    private static final BigDecimal NEAR_LIMIT = new BigDecimal("0.8");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal TWELVE = new BigDecimal("12");

    /** Monthly revenue of one company. */
    public record MonthRevenue(BigDecimal ownProductionSales, BigDecimal resales, BigDecimal services,
                               BigDecimal salesReturns, BigDecimal purchases) {

        public static final MonthRevenue EMPTY = new MonthRevenue(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO);

        public BigDecimal net() {
            return ownProductionSales.add(resales).add(services).subtract(salesReturns);
        }

        public MonthRevenue add(MonthRevenue other) {
            return new MonthRevenue(ownProductionSales.add(other.ownProductionSales), resales.add(other.resales),
                    services.add(other.services), salesReturns.add(other.salesReturns), purchases.add(other.purchases));
        }

        public MonthRevenue plus(RevenueCfops.Kind kind, BigDecimal value) {
            return switch (kind) {
                case OWN_PRODUCTION_SALE -> new MonthRevenue(ownProductionSales.add(value), resales, services, salesReturns, purchases);
                case RESALE -> new MonthRevenue(ownProductionSales, resales.add(value), services, salesReturns, purchases);
                case SERVICE -> new MonthRevenue(ownProductionSales, resales, services.add(value), salesReturns, purchases);
                case SALES_RETURN -> new MonthRevenue(ownProductionSales, resales, services, salesReturns.add(value), purchases);
                case PURCHASE -> new MonthRevenue(ownProductionSales, resales, services, salesReturns, purchases.add(value));
                case NONE -> this;
            };
        }

        /**
         * Revenue per Simples annex: returns are goods, so they come off resale first, then
         * own production; services go to the company's service annex.
         */
        BigDecimal[] goodsAfterReturns() {
            BigDecimal resaleNet = resales.subtract(salesReturns);
            BigDecimal productionNet = ownProductionSales;
            if (resaleNet.signum() < 0) {
                productionNet = productionNet.add(resaleNet).max(BigDecimal.ZERO);
                resaleNet = BigDecimal.ZERO;
            }
            return new BigDecimal[]{resaleNet, productionNet};
        }
    }

    /**
     * What the calculator gets for one company and period. {@code regime} is the one in
     * force in the month (regime history); {@code activityStart} is the opening date when
     * known, else the month of the first sale note.
     */
    public record Input(Company company, YearMonth month, CompanyTaxRegimeService.RegimeAt regime,
                        Map<YearMonth, MonthRevenue> revenueByMonth, long invoicesIssued, YearMonth activityStart,
                        boolean withHistory) {
    }

    private TaxAssessmentCalculator() {
    }

    public static TaxAssessmentDTO assess(Input input) {
        Company company = input.company();
        YearMonth month = input.month();
        MonthRevenue current = revenueOf(input, month);
        CompanyTaxRegimeService.RegimeAt regimeAt = input.regime();
        TaxRegime regime = regimeAt == null ? null : regimeAt.regime();

        Rbt12 rbt12 = rbt12(input);
        BigDecimal yearToDate = BigDecimal.ZERO;
        for (YearMonth m = YearMonth.of(month.getYear(), 1); !m.isAfter(month); m = m.plusMonths(1)) {
            yearToDate = yearToDate.add(revenueOf(input, m).net());
        }

        List<TaxEstimateDTO> taxes = new ArrayList<>();
        List<AssessmentAlertDTO> alerts = new ArrayList<>();
        if (regime == null || regime == TaxRegime.OTHER) {
            alerts.add(new AssessmentAlertDTO(Level.WARNING, "REGIME_MISSING",
                    "Regime tributário não informado no cadastro da empresa - sem estimativa de impostos."));
        } else {
            switch (regime) {
                case SIMPLES_NACIONAL -> simples(regimeAt.serviceAnnex(), current, rbt12, taxes, alerts);
                case MEI -> mei(yearToDate, taxes, alerts);
                case LUCRO_PRESUMIDO -> presumido(current, taxes);
                case LUCRO_REAL -> real(current, taxes);
                default -> {
                }
            }
        }
        if (input.invoicesIssued() == 0) {
            alerts.add(new AssessmentAlertDTO(Level.WARNING, "NO_INVOICES",
                    "Nenhuma nota de saída no mês - confira se houve faturamento sem nota ou se a sincronização está em dia."));
        }
        if (rbt12.proportional() && regime == TaxRegime.SIMPLES_NACIONAL) {
            alerts.add(new AssessmentAlertDTO(Level.INFO, "RBT12_PROPORTIONAL",
                    "Menos de 12 meses de notas antes do período: RBT12 proporcionalizado pela média dos meses com notas."));
        }

        BigDecimal totalTaxes = taxes.stream().map(TaxEstimateDTO::amount).filter(a -> a != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<MonthRevenueDTO> history = new ArrayList<>();
        if (input.withHistory()) {
            for (YearMonth m = month.minusMonths(12); !m.isAfter(month); m = m.plusMonths(1)) {
                history.add(toDto(m, revenueOf(input, m)));
            }
        }

        return new TaxAssessmentDTO(
                company.getId().toString(),
                company.getName(),
                company.getDocument() == null ? null : company.getDocument().getNumber(),
                month.toString(),
                regime,
                regimeAt == null ? null : regimeAt.label(),
                regimeAt == null ? null : regimeAt.activity(),
                regimeAt == null ? null : regimeAt.serviceAnnex(),
                toDto(month, current),
                input.invoicesIssued(),
                rbt12.value(),
                rbt12.proportional(),
                money(yearToDate),
                taxes,
                money(totalTaxes),
                alerts,
                history,
                null,
                null
        );
    }

    // ------------------------------------------------------------------ RBT12

    record Rbt12(BigDecimal value, boolean proportional) {
    }

    /**
     * Revenue of the 12 months before the period. In the first months of activity (LC 123,
     * art. 18 §2) it is the monthly average of the months so far × 12 - months since the
     * opening date (or the first sale note); the very first month uses its own revenue × 12.
     */
    static Rbt12 rbt12(Input input) {
        YearMonth month = input.month();
        YearMonth windowStart = month.minusMonths(12);
        YearMonth first = input.activityStart();
        if (first == null || !first.isAfter(windowStart)) {
            BigDecimal sum = BigDecimal.ZERO;
            for (YearMonth m = windowStart; m.isBefore(month); m = m.plusMonths(1)) {
                sum = sum.add(revenueOf(input, m).net());
            }
            return new Rbt12(money(sum), false);
        }
        if (!first.isBefore(month)) {
            return new Rbt12(money(revenueOf(input, month).net().multiply(TWELVE)), true);
        }
        BigDecimal sum = BigDecimal.ZERO;
        int months = 0;
        for (YearMonth m = first; m.isBefore(month); m = m.plusMonths(1)) {
            sum = sum.add(revenueOf(input, m).net());
            months++;
        }
        return new Rbt12(money(sum.divide(BigDecimal.valueOf(months), 10, RoundingMode.HALF_UP).multiply(TWELVE)), true);
    }

    // ------------------------------------------------------------------ regimes

    private static void simples(SimplesAnnex configuredServiceAnnex, MonthRevenue current, Rbt12 rbt12, List<TaxEstimateDTO> taxes,
                                List<AssessmentAlertDTO> alerts) {
        BigDecimal base = rbt12.value().max(BigDecimal.ZERO);
        BigDecimal[] goods = current.goodsAfterReturns();
        SimplesAnnex serviceAnnex = configuredServiceAnnex == null ? SimplesAnnex.III : configuredServiceAnnex;
        int bracket = SimplesAnnex.bracketOf(base);
        addSimples(taxes, SimplesAnnex.I, goods[0], base, bracket, "Revenda de mercadorias");
        addSimples(taxes, SimplesAnnex.II, goods[1], base, bracket, "Venda de produção própria");
        addSimples(taxes, serviceAnnex, current.services(), base, bracket, "Serviços");
        if (taxes.isEmpty()) {
            taxes.add(new TaxEstimateDTO("DAS", "DAS (Simples Nacional)", BigDecimal.ZERO, null, BigDecimal.ZERO,
                    "Sem receita no mês."));
        }

        if (base.compareTo(SIMPLES_CEILING) > 0) {
            alerts.add(new AssessmentAlertDTO(Level.DANGER, "SIMPLES_CEILING",
                    "RBT12 acima de R$ 4,8 milhões: a empresa está sujeita à exclusão do Simples Nacional."));
        } else if (base.compareTo(SIMPLES_SUBLIMIT) > 0) {
            alerts.add(new AssessmentAlertDTO(Level.WARNING, "SIMPLES_SUBLIMIT",
                    "RBT12 acima do sublimite de R$ 3,6 milhões: ICMS e ISS passam a ser recolhidos fora do DAS."));
        } else if (base.compareTo(SIMPLES_SUBLIMIT.multiply(NEAR_LIMIT)) >= 0) {
            alerts.add(new AssessmentAlertDTO(Level.INFO, "SIMPLES_NEAR_SUBLIMIT",
                    "RBT12 acima de 80% do sublimite de R$ 3,6 milhões."));
        }
    }

    private static void addSimples(List<TaxEstimateDTO> taxes, SimplesAnnex annex, BigDecimal revenue, BigDecimal rbt12,
                                   int bracket, String what) {
        if (revenue.signum() <= 0) {
            return;
        }
        BigDecimal rate = annex.effectiveRate(rbt12);
        taxes.add(new TaxEstimateDTO("DAS_" + annex.name(), "DAS - Anexo " + annex.name(), money(revenue), rate,
                percentOf(revenue, rate), what + ", " + bracket + "ª faixa"));
    }

    private static void mei(BigDecimal yearToDate, List<TaxEstimateDTO> taxes, List<AssessmentAlertDTO> alerts) {
        taxes.add(new TaxEstimateDTO("DAS_MEI", "DAS-MEI", null, null, null,
                "Valor fixo mensal (INSS + ICMS/ISS) - não depende do faturamento."));
        if (yearToDate.compareTo(MEI_TOLERANCE) > 0) {
            alerts.add(new AssessmentAlertDTO(Level.DANGER, "MEI_EXCLUSION",
                    "Faturamento do ano acima de R$ 97,2 mil (limite + 20%): desenquadramento do MEI retroativo a janeiro."));
        } else if (yearToDate.compareTo(MEI_LIMIT) > 0) {
            alerts.add(new AssessmentAlertDTO(Level.WARNING, "MEI_EXCESS",
                    "Faturamento do ano acima de R$ 81 mil: o excesso é tributado e o MEI sai do regime no ano seguinte."));
        } else if (yearToDate.compareTo(MEI_LIMIT.multiply(NEAR_LIMIT)) >= 0) {
            alerts.add(new AssessmentAlertDTO(Level.INFO, "MEI_NEAR_LIMIT", "Faturamento do ano acima de 80% do limite do MEI."));
        }
    }

    /**
     * PIS/COFINS cumulativos on the month; IRPJ/CSLL on the presumed profit (8%/12% goods,
     * 32% services) - they are quarterly, the month is a preview.
     */
    private static void presumido(MonthRevenue current, List<TaxEstimateDTO> taxes) {
        BigDecimal net = current.net().max(BigDecimal.ZERO);
        BigDecimal goods = current.net().subtract(current.services()).max(BigDecimal.ZERO);
        BigDecimal services = current.services().max(BigDecimal.ZERO);
        taxes.add(tax("PIS", "PIS (cumulativo)", net, "0.65", null));
        taxes.add(tax("COFINS", "COFINS (cumulativo)", net, "3.00", null));

        BigDecimal irpjBase = percentOf(goods, new BigDecimal("8")).add(percentOf(services, new BigDecimal("32")));
        BigDecimal irpj = percentOf(irpjBase, new BigDecimal("15"));
        BigDecimal additionalBase = irpjBase.subtract(new BigDecimal("20000")).max(BigDecimal.ZERO);
        irpj = irpj.add(percentOf(additionalBase, BigDecimal.TEN));
        taxes.add(new TaxEstimateDTO("IRPJ", "IRPJ (presumido)", irpjBase, new BigDecimal("15.0000"), irpj,
                "Base presumida: 8% das vendas + 32% dos serviços; adicional de 10% acima de R$ 20 mil/mês. Apuração trimestral."));
        BigDecimal csllBase = percentOf(goods, new BigDecimal("12")).add(percentOf(services, new BigDecimal("32")));
        taxes.add(new TaxEstimateDTO("CSLL", "CSLL (presumido)", csllBase, new BigDecimal("9.0000"),
                percentOf(csllBase, new BigDecimal("9")),
                "Base presumida: 12% das vendas + 32% dos serviços. Apuração trimestral."));
    }

    /** PIS/COFINS não cumulativos: debit on revenue minus credit on purchases (9,25%). IRPJ/CSLL need the books. */
    private static void real(MonthRevenue current, List<TaxEstimateDTO> taxes) {
        BigDecimal net = current.net().max(BigDecimal.ZERO);
        BigDecimal purchases = current.purchases();
        BigDecimal pis = percentOf(net, new BigDecimal("1.65")).subtract(percentOf(purchases, new BigDecimal("1.65"))).max(BigDecimal.ZERO);
        BigDecimal cofins = percentOf(net, new BigDecimal("7.60")).subtract(percentOf(purchases, new BigDecimal("7.60"))).max(BigDecimal.ZERO);
        String creditNote = "Débito sobre a receita menos crédito sobre compras de R$ " + money(purchases).toPlainString() + ".";
        taxes.add(new TaxEstimateDTO("PIS", "PIS (não cumulativo)", money(net), new BigDecimal("1.6500"), pis, creditNote));
        taxes.add(new TaxEstimateDTO("COFINS", "COFINS (não cumulativo)", money(net), new BigDecimal("7.6000"), cofins, creditNote));
        taxes.add(new TaxEstimateDTO("IRPJ_CSLL", "IRPJ e CSLL", null, null, null,
                "Dependem do lucro contábil (ou da estimativa mensal) - não dá para estimar só pelas notas."));
    }

    // ------------------------------------------------------------------ helpers

    private static TaxEstimateDTO tax(String code, String label, BigDecimal base, String rate, String note) {
        BigDecimal percent = new BigDecimal(rate);
        return new TaxEstimateDTO(code, label, money(base), percent.setScale(4, RoundingMode.HALF_UP), percentOf(base, percent), note);
    }

    private static MonthRevenue revenueOf(Input input, YearMonth month) {
        return input.revenueByMonth().getOrDefault(month, MonthRevenue.EMPTY);
    }

    private static MonthRevenueDTO toDto(YearMonth month, MonthRevenue revenue) {
        return new MonthRevenueDTO(month.toString(), money(revenue.ownProductionSales()), money(revenue.resales()),
                money(revenue.services()), money(revenue.salesReturns()), money(revenue.net()));
    }

    static BigDecimal percentOf(BigDecimal value, BigDecimal percent) {
        return value.multiply(percent).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
