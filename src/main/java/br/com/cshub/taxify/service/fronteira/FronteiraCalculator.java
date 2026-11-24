package br.com.cshub.taxify.service.fronteira;

import br.com.cshub.taxify.domain.fronteira.FronteiraRule;
import br.com.cshub.taxify.domain.xml.FiscalItemTaxDetail;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocumentItem;
import br.com.cshub.taxify.enumerated.FronteiraRuleKind;
import br.com.cshub.taxify.service.EntryCfops;
import br.com.cshub.taxify.service.fiscalcheck.OperationCheck;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Taxify's own ICMS antecipado of an interstate entry into PE, item by item.
 * <p>
 * Each item is classified by its CFOP and the table, then calculated:
 * <ol>
 *   <li>a FORMULA rule that matches the item (product, supplier, NCM, company) takes the place of
 *       everything else - the accountant's own way, kept for the next notes;</li>
 *   <li>otherwise the standard calculation, with the rates of the table:
 *     <ul>
 *       <li>devolução, remessa, outras saídas → no antecipação;</li>
 *       <li>ST already withheld by the supplier → nothing more (encerrada na origem);</li>
 *       <li>product under ST (ST_MVA rule) → MVA ajustada = [(1 + MVA) × (1 − inter) ÷ (1 − interna)] − 1 and
 *           ICMS-ST = partida × (1 + MVA ajustada) × interna − ICMS de origem;</li>
 *       <li>Simples Nacional company with the art. 363-A percentage (SIMPLES_PERCENTUAL) → partida × percentual;</li>
 *       <li>otherwise (sem encerramento, and uso e consumo) → base = (partida − ICMS destacado) ÷ (1 − interna),
 *           ICMS = base × interna − ICMS de origem.</li>
 *     </ul></li>
 * </ol>
 * SEFAZ-PE rounds in its own way, so every item is calculated in four ways (A: no rounding until
 * the end; B: each step rounded; C: MVA ajustada to 4 places then each step rounded; D: each step
 * truncated) and the note conferes when the charged value falls in that range, with a tolerance.
 * An item without a rate it needs is "sem regra" - never "cobrou a mais".
 */
public final class FronteiraCalculator {

    static final String UF = "PE";
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public enum Treatment {
        FORMULA("Fórmula própria"),
        ST("Com encerramento (ST)"),
        ST_RETIDO("ST retido na origem"),
        SIMPLES("Simples Nacional - art. 363-A"),
        SEM_ENCERRAMENTO("Sem encerramento"),
        USO_CONSUMO("Uso e consumo / ativo - diferencial"),
        SEM_ANTECIPACAO("Sem antecipação (devolução, remessa ou outras)"),
        SEM_REGRA("Sem regra");

        public final String label;

        Treatment(String label) {
            this.label = label;
        }
    }

    /** What the calculation knows about the company and the period. */
    public record Context(List<FronteiraRule> rules, boolean simplesCompany, String credenciamento) {
    }

    /**
     * One item: {@code value} is method C (the one expected to be SEFAZ's), {@code min}/{@code max} the
     * range over the four methods. {@code reviewed} false when a rule used hasn't been checked yet.
     */
    public record ItemResult(int numeroItem, String codigo, String descricao, String ncm, String cfop, Treatment treatment,
                             BigDecimal partida, BigDecimal value, BigDecimal min, BigDecimal max, boolean reviewed,
                             List<UUID> rulesUsed, List<String> memoria, String semRegraMotivo) {
    }

    public record NoteResult(BigDecimal value, BigDecimal min, BigDecimal max, boolean reviewed, boolean semRegra,
                             List<ItemResult> items) {
    }

    private FronteiraCalculator() {
    }

    public static NoteResult calculate(FiscalXmlDocument document, List<FiscalXmlDocumentItem> items, Context context) {
        List<ItemResult> results = new ArrayList<>();
        BigDecimal value = BigDecimal.ZERO;
        BigDecimal min = BigDecimal.ZERO;
        BigDecimal max = BigDecimal.ZERO;
        boolean reviewed = true;
        boolean semRegra = false;
        List<FiscalXmlDocumentItem> sorted = items.stream()
                .sorted(Comparator.comparing(FiscalXmlDocumentItem::getNumeroItem, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        for (FiscalXmlDocumentItem item : sorted) {
            ItemResult result = item(document, item, context);
            results.add(result);
            if (result.treatment() == Treatment.SEM_REGRA) {
                semRegra = true;
                continue;
            }
            value = value.add(result.value());
            min = min.add(result.min());
            max = max.add(result.max());
            reviewed &= result.reviewed();
        }
        return new NoteResult(value, min, max, reviewed, semRegra, List.copyOf(results));
    }

    // ---------------------------------------------------------------- one item

    static ItemResult item(FiscalXmlDocument document, FiscalXmlDocumentItem item, Context context) {
        LocalDate day = document.getIssueDate() == null ? null : document.getIssueDate().toLocalDate();
        Values v = values(document, item);
        List<String> memoria = new ArrayList<>();
        List<UUID> used = new ArrayList<>();
        memoria.add("Partida: produto %s + IPI %s + despesas rateadas %s = %s".formatted(money(v.valorProduto), money(v.ipi),
                money(v.despesas), money(v.partida)));

        // the accountant's own formula wins over the standard calculation
        Optional<FronteiraRule> formula = bestMatch(context.rules(), FronteiraRuleKind.FORMULA, document, item, day);
        Optional<FronteiraRule> interna = bestMatch(context.rules(), FronteiraRuleKind.ALIQUOTA_INTERNA, document, item, day);
        Optional<FronteiraRule> st = bestMatch(context.rules(), FronteiraRuleKind.ST_MVA, document, item, day);
        Optional<FronteiraRule> simples = context.simplesCompany()
                ? simplesRule(context.rules(), document, item, day, context.credenciamento()) : Optional.empty();
        BigDecimal inter = interstateRate(document, item);

        Map<String, BigDecimal> variables = new LinkedHashMap<>();
        variables.put("partida", v.partida);
        variables.put("valor_produto", v.valorProduto);
        variables.put("ipi", v.ipi);
        variables.put("despesas", v.despesas);
        variables.put("icms_origem", v.icmsOrigem);
        variables.put("aliquota_origem", fraction(item.getAliquotaIcms()));
        variables.put("inter", inter == null ? BigDecimal.ZERO : inter);
        variables.put("interna", interna.map(rule -> fraction(rule.getAliquota())).orElse(BigDecimal.ZERO));
        variables.put("mva", st.map(rule -> fraction(rule.getMva())).orElse(BigDecimal.ZERO));
        variables.put("mva_ajustada", st.isPresent() && interna.isPresent() && inter != null
                ? mvaAjustada(fraction(st.get().getMva()), inter, fraction(interna.get().getAliquota()), UnaryOperator.identity())
                : BigDecimal.ZERO);
        variables.put("st_retido", v.stRetido);
        variables.put("percentual", simples.map(rule -> fraction(rule.getPercentual())).orElse(BigDecimal.ZERO));

        if (formula.isPresent()) {
            FronteiraRule rule = formula.get();
            used.add(rule.getId());
            memoria.add("Fórmula própria (%s): %s".formatted(scope(rule), rule.getFormula()));
            for (String name : FormulaEvaluator.variablesIn(rule.getFormula())) {
                memoria.add("  %s = %s".formatted(name, plain(variables.get(name))));
            }
            try {
                BigDecimal raw = FormulaEvaluator.evaluate(rule.getFormula(), variables);
                BigDecimal up = raw.setScale(2, RoundingMode.HALF_UP);
                BigDecimal down = raw.setScale(2, RoundingMode.DOWN);
                memoria.add("Resultado: %s".formatted(money(up)));
                return result(item, Treatment.FORMULA, v.partida, up, down.min(up), down.max(up), rule.isReviewed(), used, memoria, null);
            } catch (FormulaEvaluator.FormulaException e) {
                return semRegra(item, v.partida, used, memoria, "A fórmula própria não pôde ser calculada: " + e.getMessage());
            }
        }

        String entry = EntryCfops.fromSupplier(item.getCfop());
        String operation = entry != null && entry.length() == 4 ? entry.substring(1) : "";
        if (operation.startsWith("2") || operation.startsWith("9") || Set.of("410", "411").contains(operation)) {
            memoria.add("CFOP %s (entrada %s): devolução, remessa ou outras - sem antecipação".formatted(item.getCfop(), entry));
            return result(item, Treatment.SEM_ANTECIPACAO, v.partida, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, true, used, memoria, null);
        }
        if (v.stRetido.signum() > 0) {
            memoria.add("ICMS-ST de %s já retido pelo fornecedor - antecipação encerrada na origem".formatted(money(v.stRetido)));
            return result(item, Treatment.ST_RETIDO, v.partida, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, true, used, memoria, null);
        }
        if (st.isEmpty() && context.simplesCompany()) {
            if (simples.isEmpty()) {
                return semRegra(item, v.partida, used, memoria, "Empresa do Simples sem o percentual do art. 363-A na tabela"
                        + (context.credenciamento() == null ? "." : " para o credenciamento \"" + context.credenciamento() + "\"."));
            }
            FronteiraRule rule = simples.get();
            used.add(rule.getId());
            BigDecimal percentual = fraction(rule.getPercentual());
            BigDecimal raw = v.partida.multiply(percentual, MC);
            BigDecimal up = raw.setScale(2, RoundingMode.HALF_UP);
            BigDecimal down = raw.setScale(2, RoundingMode.DOWN);
            memoria.add("Simples Nacional, art. 363-A (%s): %s × %s = %s".formatted(scope(rule), money(v.partida), percent(percentual), money(up)));
            return result(item, Treatment.SIMPLES, v.partida, up, down, up, rule.isReviewed(), used, memoria, null);
        }
        if (inter == null) {
            return semRegra(item, v.partida, used, memoria, "Alíquota interestadual desconhecida (UF do emitente ou origem da mercadoria em branco).");
        }
        memoria.add("Alíquota interestadual: %s (de %s para %s, origem %s)".formatted(percent(inter), document.getEmitenteUf(),
                UF, originOf(item)));
        if (interna.isEmpty()) {
            return semRegra(item, v.partida, used, memoria, "Sem alíquota interna de PE na tabela de ICMS para o NCM " + item.getNcm() + ".");
        }
        BigDecimal internalRate = fraction(interna.get().getAliquota());
        used.add(interna.get().getId());
        memoria.add("Alíquota interna de PE: %s (%s)".formatted(percent(internalRate), scope(interna.get())));
        boolean reviewed = interna.get().isReviewed();

        if (st.isPresent()) {
            FronteiraRule rule = st.get();
            used.add(rule.getId());
            reviewed &= rule.isReviewed();
            BigDecimal mva = fraction(rule.getMva());
            BigDecimal[] range = range(r -> stValue(v, mva, inter, internalRate, r, false),
                    r -> stValue(v, mva, inter, internalRate, r, true));
            memoria.add("Produto com ST (%s): MVA original %s".formatted(scope(rule), percent(mva)));
            memoria.add("MVA ajustada = [(1 + %s) × (1 − %s) ÷ (1 − %s)] − 1 = %s".formatted(plain(mva), plain(inter), plain(internalRate),
                    percent(mvaAjustada(mva, inter, internalRate, r4()))));
            memoria.add("ICMS-ST = %s × (1 + MVA ajustada) × %s − ICMS de origem %s = %s".formatted(money(v.partida), plain(internalRate),
                    money(v.icmsOrigem), money(range[2])));
            return result(item, Treatment.ST, v.partida, range[2], range[0], range[1], reviewed, used, memoria, null);
        }

        boolean usoConsumo = Set.of("551", "552", "553", "555", "556", "557").contains(operation);
        BigDecimal[] range = range(r -> diferencial(v, internalRate, r), r -> diferencial(v, internalRate, r));
        memoria.add("Base = (%s − %s) ÷ (1 − %s); ICMS = base × %s − %s = %s".formatted(money(v.partida), money(v.icmsOrigem),
                plain(internalRate), plain(internalRate), money(v.icmsOrigem), money(range[2])));
        return result(item, usoConsumo ? Treatment.USO_CONSUMO : Treatment.SEM_ENCERRAMENTO, v.partida, range[2], range[0], range[1],
                reviewed, used, memoria, null);
    }

    // ---------------------------------------------------------------- formulas

    /** ICMS-ST = partida × (1 + MVA ajustada) × interna − ICMS de origem; {@code adjustedTo4} rounds the MVA first (method C). */
    private static BigDecimal stValue(Values v, BigDecimal mva, BigDecimal inter, BigDecimal interna, UnaryOperator<BigDecimal> round,
                                      boolean adjustedTo4) {
        BigDecimal adjusted = mvaAjustada(mva, inter, interna, adjustedTo4 ? r4() : UnaryOperator.identity());
        BigDecimal base = round.apply(v.partida.multiply(BigDecimal.ONE.add(adjusted), MC));
        BigDecimal tax = round.apply(base.multiply(interna, MC));
        return tax.subtract(v.icmsOrigem).max(BigDecimal.ZERO);
    }

    /** base = (partida − ICMS destacado) ÷ (1 − interna); ICMS = base × interna − ICMS de origem. */
    private static BigDecimal diferencial(Values v, BigDecimal interna, UnaryOperator<BigDecimal> round) {
        BigDecimal base = round.apply(v.partida.subtract(v.icmsOrigem).divide(BigDecimal.ONE.subtract(interna), MC));
        BigDecimal tax = round.apply(base.multiply(interna, MC));
        return tax.subtract(v.icmsOrigem).max(BigDecimal.ZERO);
    }

    static BigDecimal mvaAjustada(BigDecimal mva, BigDecimal inter, BigDecimal interna, UnaryOperator<BigDecimal> round) {
        return round.apply(BigDecimal.ONE.add(mva).multiply(BigDecimal.ONE.subtract(inter), MC)
                .divide(BigDecimal.ONE.subtract(interna), MC).subtract(BigDecimal.ONE));
    }

    /**
     * {min, max, C} over the four methods: A no rounding until the end, B each step rounded, C (second
     * function) the method expected to be SEFAZ's, D each step truncated.
     */
    private static BigDecimal[] range(java.util.function.Function<UnaryOperator<BigDecimal>, BigDecimal> calculation,
                                      java.util.function.Function<UnaryOperator<BigDecimal>, BigDecimal> methodC) {
        BigDecimal a = calculation.apply(UnaryOperator.identity()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal b = calculation.apply(r2(RoundingMode.HALF_UP)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal c = methodC.apply(r2(RoundingMode.HALF_UP)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal d = calculation.apply(r2(RoundingMode.DOWN)).setScale(2, RoundingMode.DOWN);
        BigDecimal min = a.min(b).min(c).min(d);
        BigDecimal max = a.max(b).max(c).max(d);
        return new BigDecimal[]{min, max, c};
    }

    private static UnaryOperator<BigDecimal> r2(RoundingMode mode) {
        return value -> value.setScale(2, mode);
    }

    private static UnaryOperator<BigDecimal> r4() {
        return value -> value.setScale(4, RoundingMode.HALF_UP);
    }

    // ---------------------------------------------------------------- rule matching

    /**
     * The most specific rule of that kind in force on the day that matches the item: every criterion
     * filled in must match; product beats supplier beats CEST beats a longer NCM prefix, and the
     * company's own rule beats a global one.
     */
    static Optional<FronteiraRule> bestMatch(List<FronteiraRule> rules, FronteiraRuleKind kind, FiscalXmlDocument document,
                                             FiscalXmlDocumentItem item, LocalDate day) {
        String ncm = digits(item.getNcm());
        String cest = item.getTaxDetail() == null ? "" : digits(item.getTaxDetail().cest());
        String codigo = item.getCodigo() == null ? "" : item.getCodigo().strip();
        String supplier = digits(document.getIssuerCnpj());
        return rules.stream()
                .filter(rule -> rule.getKind() == kind && rule.isEnabled() && !rule.isDeleted() && rule.validOn(day))
                .filter(rule -> blank(rule.getNcm()) || ncm.startsWith(digits(rule.getNcm())))
                .filter(rule -> blank(rule.getCest()) || cest.equals(digits(rule.getCest())))
                .filter(rule -> blank(rule.getCodigoProduto()) || codigo.equalsIgnoreCase(rule.getCodigoProduto().strip()))
                .filter(rule -> blank(rule.getCnpjFornecedor()) || supplier.equals(digits(rule.getCnpjFornecedor())))
                .max(Comparator.comparingInt(FronteiraCalculator::specificity)
                        .thenComparing(rule -> rule.getCreatedDate() == null ? java.time.LocalDateTime.MIN : rule.getCreatedDate()));
    }

    private static int specificity(FronteiraRule rule) {
        int score = 0;
        if (!blank(rule.getCodigoProduto())) score += 1000;
        if (!blank(rule.getCnpjFornecedor())) score += 500;
        if (!blank(rule.getCest())) score += 100;
        if (!blank(rule.getNcm())) score += 10 + digits(rule.getNcm()).length();
        if (rule.getCompany() != null) score += 1;
        return score;
    }

    /** The art. 363-A rule whose description is the extract's credenciamento; otherwise the only one there is. */
    private static Optional<FronteiraRule> simplesRule(List<FronteiraRule> rules, FiscalXmlDocument document, FiscalXmlDocumentItem item,
                                                       LocalDate day, String credenciamento) {
        List<FronteiraRule> candidates = rules.stream()
                .filter(rule -> rule.getKind() == FronteiraRuleKind.SIMPLES_PERCENTUAL && rule.isEnabled() && !rule.isDeleted() && rule.validOn(day))
                .toList();
        if (credenciamento != null) {
            String wanted = plainText(credenciamento);
            List<FronteiraRule> named = candidates.stream()
                    .filter(rule -> !blank(rule.getDescricao()) && plainText(rule.getDescricao()).equals(wanted)).toList();
            if (!named.isEmpty()) {
                return bestMatch(named, FronteiraRuleKind.SIMPLES_PERCENTUAL, document, item, day);
            }
        }
        List<FronteiraRule> unnamed = candidates.stream().filter(rule -> blank(rule.getDescricao())).toList();
        return bestMatch(unnamed.isEmpty() && candidates.size() == 1 ? candidates : unnamed,
                FronteiraRuleKind.SIMPLES_PERCENTUAL, document, item, day);
    }

    // ---------------------------------------------------------------- values

    private record Values(BigDecimal valorProduto, BigDecimal ipi, BigDecimal despesas, BigDecimal partida,
                          BigDecimal icmsOrigem, BigDecimal stRetido) {
    }

    /** The note's freight, insurance and other expenses go to the items by product value; the discount doesn't reduce. */
    private static Values values(FiscalXmlDocument document, FiscalXmlDocumentItem item) {
        BigDecimal produto = zero(item.getValorTotal());
        BigDecimal ipi = zero(item.getValorIpi());
        BigDecimal expenses = zero(document.getValorFrete()).add(zero(document.getValorSeguro())).add(zero(document.getValorOutrasDespesas()));
        BigDecimal products = zero(document.getValorProdutos());
        BigDecimal despesas = expenses.signum() == 0 || products.signum() == 0 ? BigDecimal.ZERO
                : expenses.multiply(produto, MC).divide(products, 2, RoundingMode.HALF_UP);
        FiscalItemTaxDetail detail = item.getTaxDetail();
        BigDecimal st = detail == null ? BigDecimal.ZERO : zero(detail.valorIcmsSt());
        return new Values(produto, ipi, despesas, produto.add(ipi).add(despesas), zero(item.getValorIcms()), st);
    }

    /** By origin and UF (Res. Senado 22/1989 and 13/2012); the item's own rate when that can't be told. */
    private static BigDecimal interstateRate(FiscalXmlDocument document, FiscalXmlDocumentItem item) {
        String origin = originOf(item);
        String from = document.getEmitenteUf() == null ? null : document.getEmitenteUf().strip().toUpperCase(Locale.ROOT);
        BigDecimal expected = OperationCheck.expectedInterstateRate(origin, from, UF);
        if (expected != null) {
            return fraction(expected);
        }
        return item.getAliquotaIcms() == null || item.getAliquotaIcms().signum() == 0 ? null : fraction(item.getAliquotaIcms());
    }

    private static String originOf(FiscalXmlDocumentItem item) {
        return item.getTaxDetail() == null ? null : item.getTaxDetail().origemMercadoria();
    }

    private static ItemResult result(FiscalXmlDocumentItem item, Treatment treatment, BigDecimal partida, BigDecimal value, BigDecimal min,
                                     BigDecimal max, boolean reviewed, List<UUID> used, List<String> memoria, String motivo) {
        return new ItemResult(item.getNumeroItem() == null ? 0 : item.getNumeroItem(), item.getCodigo(), item.getDescricao(), item.getNcm(),
                item.getCfop(), treatment, partida, value, min, max, reviewed, List.copyOf(used), List.copyOf(memoria), motivo);
    }

    private static ItemResult semRegra(FiscalXmlDocumentItem item, BigDecimal partida, List<UUID> used, List<String> memoria, String motivo) {
        memoria.add("Sem regra: " + motivo);
        return result(item, Treatment.SEM_REGRA, partida, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false, used, memoria, motivo);
    }

    private static String scope(FronteiraRule rule) {
        List<String> parts = new ArrayList<>();
        if (!blank(rule.getCodigoProduto())) parts.add("produto " + rule.getCodigoProduto());
        if (!blank(rule.getCnpjFornecedor())) parts.add("fornecedor " + rule.getCnpjFornecedor());
        if (!blank(rule.getCest())) parts.add("CEST " + rule.getCest());
        if (!blank(rule.getNcm())) parts.add("NCM " + rule.getNcm());
        if (rule.getCompany() != null) parts.add("desta empresa");
        String where = parts.isEmpty() ? "geral" : String.join(", ", parts);
        return rule.isReviewed() ? where : where + ", a conferir";
    }

    static BigDecimal fraction(BigDecimal percent) {
        return percent == null ? BigDecimal.ZERO : percent.divide(HUNDRED, MC);
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String digits(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }

    private static String plainText(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "").replaceAll("\\s+", " ")
                .strip().toUpperCase(Locale.ROOT);
    }

    private static String money(BigDecimal value) {
        return "R$ " + String.format(Locale.forLanguageTag("pt-BR"), "%,.2f", value == null ? BigDecimal.ZERO : value);
    }

    private static String percent(BigDecimal fraction) {
        return String.format(Locale.forLanguageTag("pt-BR"), "%.2f%%", fraction.multiply(HUNDRED));
    }

    private static String plain(BigDecimal value) {
        return value == null ? "0" : value.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().replace('.', ',');
    }
}
