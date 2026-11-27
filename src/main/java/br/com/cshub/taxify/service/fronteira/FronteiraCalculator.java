package br.com.cshub.taxify.service.fronteira;

import br.com.cshub.taxify.domain.fiscalrule.ProductFiscalRule;
import br.com.cshub.taxify.domain.fiscalrule.ProductRules;
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
 *   <li>devolução, remessa and ST already withheld are no antecipação - never replaced;</li>
 *   <li>a FORMULA rule that matches the item (product, supplier, NCM, company) and the treatment it
 *       replaces ({@code aplicaEm}, blank = any) takes the place of the standard calculation - the
 *       accountant's own way, kept for the next notes;</li>
 *   <li>otherwise the standard calculation, with the rates of the table:
 *     <ul>
 *       <li>product under ST (ST_MVA rule) → MVA ajustada = [(1 + MVA) × (1 − inter) ÷ (1 − interna)] − 1 and
 *           ICMS-ST = partida × (1 + MVA ajustada) × interna − crédito de origem;</li>
 *       <li>carga líquida (SIMPLES_PERCENTUAL rule) → partida × percentual, no credit: the company's
 *           credenciamento (matched by the extract's name, any regime - e-Fisco's "tipo de cálculo"), or the
 *           art. 363-A for a Simples company;</li>
 *       <li>otherwise (sem encerramento, and uso e consumo) → the diferencial de alíquotas, as e-Fisco's own
 *           memória has it: ICMS = partida × (interna − inter).</li>
 *     </ul>
 *     Under ST the crédito de origem is the ICMS destacado or, from a Simples supplier, the credit it passes
 *     on (vCredICMSSN); when that can't be told yet the item is "sem regra".
 *   </li>
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
        CARGA_LIQUIDA("Carga líquida (credenciamento / Simples)"),
        MERCADORIA("Mercadoria com agregação (sem carga líquida)"),
        SEM_ENCERRAMENTO("Sem encerramento"),
        USO_CONSUMO("Uso e consumo / ativo - diferencial"),
        SEM_ANTECIPACAO("Sem antecipação (devolução, remessa ou outras)"),
        SEM_REGRA("Sem regra");

        public final String label;

        Treatment(String label) {
            this.label = label;
        }
    }

    /** Origin regions of an AGREGACAO rule (e-Fisco: CoefAgregacaoSulSudeste / NorteNE). */
    public static final String SUL_SUDESTE = "SUL_SUDESTE";
    public static final String DEMAIS_REGIOES = "DEMAIS";
    private static final Set<String> SUL_SUDESTE_UFS = Set.of("SP", "RJ", "MG", "ES", "PR", "SC", "RS");

    /**
     * What the calculation knows about the company and the period. {@code productRules} (the company's product
     * fiscal rules, may be null) give the entry CFOP the company books - an ativo bought from a supplier that
     * issued a sale is still an ativo.
     */
    public record Context(List<FronteiraRule> rules, boolean simplesCompany, String credenciamento, ProductRules productRules) {

        public Context(List<FronteiraRule> rules, boolean simplesCompany, String credenciamento) {
            this(rules, simplesCompany, credenciamento, null);
        }
    }

    /**
     * One item: {@code value} is method C (the one expected to be SEFAZ's), {@code min}/{@code max} the
     * range over the four methods. {@code reviewed} false when a rule used hasn't been checked yet.
     */
    public record ItemResult(int numeroItem, String codigo, String descricao, String ncm, String cfop, Treatment treatment,
                             BigDecimal partida, BigDecimal value, BigDecimal min, BigDecimal max, boolean reviewed,
                             List<UUID> rulesUsed, List<String> memoria, String semRegraMotivo,
                             Map<String, BigDecimal> variables) {
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
        // the values a formula can use - filled below, handed back so the screen can test a formula on this item
        Map<String, BigDecimal> variables = new LinkedHashMap<>();
        memoria.add("Partida: produto %s + IPI %s + frete %s + seguro e outras despesas %s = %s (frete e despesas rateados pelo valor do produto)"
                .formatted(money(v.valorProduto), money(v.ipi), money(v.frete), money(v.despesas.subtract(v.frete)), money(v.partida)));

        Optional<FronteiraRule> interna = bestMatch(context.rules(), FronteiraRuleKind.ALIQUOTA_INTERNA, document, item, day);
        Optional<FronteiraRule> st = bestMatch(context.rules(), FronteiraRuleKind.ST_MVA, document, item, day);
        Optional<FronteiraRule> agregacao = agregacaoRule(context.rules(), document, item, day, v.supplierSimples);
        Optional<FronteiraRule> carga = cargaLiquidaRule(context, document, item, day);
        BigDecimal inter = interstateRate(document, item);
        BigDecimal credito = inter == null ? v.icmsOrigem : credit(v, inter);

        // the entry CFOP the company books: its product fiscal rule's, else the supplier's turned into an entry
        Optional<ProductFiscalRule> productRule = context.productRules() == null ? Optional.empty()
                : context.productRules().find(item.getCodigo(), ProductRules.supplierOf(document));
        String ruleCfop = productRule.map(ProductFiscalRule::getCorrectCfop).map(String::strip).filter(cfop -> cfop.length() == 4).orElse(null);
        String entry = ruleCfop != null ? ruleCfop : EntryCfops.fromSupplier(item.getCfop());
        String operation = entry != null && entry.length() == 4 ? entry.substring(1) : "";
        if (ruleCfop != null) {
            memoria.add("CFOP de entrada %s, da regra fiscal do produto (o fornecedor emitiu %s)".formatted(ruleCfop, item.getCfop()));
        }

        // devolução, remessa and ST already withheld are never antecipação - no formula changes that
        if (operation.startsWith("2") || (operation.startsWith("9") && !TAXED_OTHERS.contains(operation))
                || Set.of("410", "411").contains(operation)) {
            memoria.add("CFOP %s (entrada %s): devolução, remessa ou outras - sem antecipação".formatted(item.getCfop(), entry));
            return result(variables, item, Treatment.SEM_ANTECIPACAO, v.partida, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, true, used, memoria, null);
        }
        if (v.stRetido.signum() > 0) {
            memoria.add("ICMS-ST de %s já retido pelo fornecedor - antecipação encerrada na origem".formatted(money(v.stRetido)));
            return result(variables, item, Treatment.ST_RETIDO, v.partida, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, true, used, memoria, null);
        }

        // the standard treatment of the item - what a formula may take the place of. Ativo and uso e consumo
        // aren't goods for resale: only the diferencial, whatever the product or the credenciamento.
        boolean usoConsumo = USO_CONSUMO_OPERATIONS.contains(operation);
        Treatment standard = usoConsumo ? Treatment.USO_CONSUMO
                : st.isPresent() ? Treatment.ST
                : agregacao.isPresent() ? Treatment.MERCADORIA
                : carga.isPresent() || context.simplesCompany() ? Treatment.CARGA_LIQUIDA
                : Treatment.SEM_ENCERRAMENTO;
        BigDecimal coef = standard == Treatment.MERCADORIA ? fraction(agregacao.get().getAggregationRate()) : BigDecimal.ZERO;

        variables.put("partida", v.partida);
        variables.put("valor_produto", v.valorProduto);
        variables.put("ipi", v.ipi);
        variables.put("despesas", v.despesas);
        variables.put("frete", v.frete);
        variables.put("icms_origem", v.icmsOrigem);
        variables.put("credito_sn", v.creditoSn == null ? BigDecimal.ZERO : v.creditoSn);
        variables.put("credito", credito);
        variables.put("aliquota_origem", fraction(item.getAliquotaIcms()));
        variables.put("inter", inter == null ? BigDecimal.ZERO : inter);
        variables.put("interna", interna.map(rule -> fraction(rule.getRate())).orElse(BigDecimal.ZERO));
        variables.put("mva", st.map(rule -> fraction(rule.getMva())).orElse(BigDecimal.ZERO));
        variables.put("mva_ajustada", st.isPresent() && interna.isPresent() && inter != null
                ? mvaAjustada(fraction(st.get().getMva()), inter, fraction(interna.get().getRate()), UnaryOperator.identity())
                : BigDecimal.ZERO);
        variables.put("agregacao", agregacao.map(rule -> fraction(rule.getAggregationRate())).orElse(BigDecimal.ZERO));
        variables.put("st_retido", v.stRetido);
        variables.put("percentual", carga.map(rule -> fraction(rule.getPercentage())).orElse(BigDecimal.ZERO));

        // the accountant's own formula for this treatment (or for all of them) wins over the standard calculation
        Optional<FronteiraRule> formula = bestMatch(context.rules().stream()
                .filter(rule -> rule.getAppliesTo() == null || rule.getAppliesTo().isBlank() || rule.getAppliesTo().equals(standard.name()))
                .toList(), FronteiraRuleKind.FORMULA, document, item, day);
        if (formula.isPresent()) {
            FronteiraRule rule = formula.get();
            used.add(rule.getId());
            memoria.add("Fórmula própria no lugar de \"%s\" (%s): %s".formatted(standard.label, scope(rule), rule.getFormula()));
            for (String name : FormulaEvaluator.variablesIn(rule.getFormula())) {
                memoria.add("  %s = %s".formatted(name, plain(variables.get(name))));
            }
            try {
                BigDecimal raw = FormulaEvaluator.evaluate(rule.getFormula(), variables);
                BigDecimal up = raw.setScale(2, RoundingMode.HALF_UP);
                BigDecimal down = raw.setScale(2, RoundingMode.DOWN);
                memoria.add("Resultado: %s".formatted(money(up)));
                return result(variables, item, Treatment.FORMULA, v.partida, up, down.min(up), down.max(up), rule.isReviewed(), used, memoria, null);
            } catch (FormulaEvaluator.FormulaException e) {
                return semRegra(variables, item, v.partida, used, memoria, "A fórmula própria não pôde ser calculada: " + e.getMessage());
            }
        }

        if (standard == Treatment.CARGA_LIQUIDA) {
            if (carga.isEmpty()) {
                return semRegra(variables, item, v.partida, used, memoria, "Empresa do Simples sem a carga líquida (art. 363-A) na tabela"
                        + (context.credenciamento() == null ? "." : " para o credenciamento \"" + context.credenciamento() + "\"."));
            }
            FronteiraRule rule = carga.get();
            used.add(rule.getId());
            BigDecimal percentual = fraction(rule.getPercentage());
            BigDecimal raw = v.partida.multiply(percentual, MC);
            BigDecimal up = raw.setScale(2, RoundingMode.HALF_UP);
            BigDecimal down = raw.setScale(2, RoundingMode.DOWN);
            memoria.add("Carga líquida (%s%s): base de partida %s × %s = %s - sem abater crédito".formatted(
                    blank(rule.getDescription()) ? "" : rule.getDescription() + ", ", scope(rule), money(v.partida), percent(percentual), money(up)));
            return result(variables, item, Treatment.CARGA_LIQUIDA, v.partida, up, down, up, rule.isReviewed(), used, memoria, null);
        }
        if (inter == null) {
            return semRegra(variables, item, v.partida, used, memoria, "Alíquota interestadual desconhecida (UF do emitente ou origem da mercadoria em branco).");
        }
        memoria.add("Alíquota interestadual: %s (de %s para %s, origem %s)".formatted(percent(inter), document.getEmitenteUf(),
                UF, originOf(item)));
        if (interna.isEmpty()) {
            return semRegra(variables, item, v.partida, used, memoria, "Sem alíquota interna de PE na tabela de ICMS para o NCM " + item.getNcm() + ".");
        }
        BigDecimal internalRate = fraction(interna.get().getRate());
        used.add(interna.get().getId());
        memoria.add("Alíquota interna de PE: %s (%s)".formatted(percent(internalRate), scope(interna.get())));
        boolean reviewed = interna.get().isReviewed();
        memoria.add(creditLine(v, inter, credito));

        if (standard == Treatment.ST) {
            FronteiraRule rule = st.get();
            used.add(rule.getId());
            reviewed &= rule.isReviewed();
            BigDecimal mva = fraction(rule.getMva());
            BigDecimal[] range = range(r -> stValue(v, credito, mva, inter, internalRate, r, false),
                    r -> stValue(v, credito, mva, inter, internalRate, r, true));
            memoria.add("Produto com ST (%s): MVA original %s".formatted(scope(rule), percent(mva)));
            memoria.add("MVA ajustada = [(1 + %s) × (1 − %s) ÷ (1 − %s)] − 1 = %s".formatted(plain(mva), plain(inter), plain(internalRate),
                    percent(mvaAjustada(mva, inter, internalRate, r4()))));
            memoria.add("ICMS-ST = %s × (1 + MVA ajustada) × %s − crédito %s = %s".formatted(money(v.partida), plain(internalRate),
                    money(credito), money(range[2])));
            return result(variables, item, Treatment.ST, v.partida, range[2], range[0], range[1], reviewed, used, memoria, null);
        }

        // e-Fisco "sem carga líquida": base = partida × (1 + agregação); ICMS = base × interna − crédito
        if (standard == Treatment.MERCADORIA) {
            used.add(agregacao.get().getId());
            reviewed &= agregacao.get().isReviewed();
            memoria.add("Mercadoria com agregação (%s): coeficiente %s".formatted(scope(agregacao.get()), plain(BigDecimal.ONE.add(coef))));
        } else if (standard == Treatment.USO_CONSUMO) {
            memoria.add("Ativo / uso e consumo (CFOP de entrada %s): só o diferencial - sem agregação e sem carga líquida".formatted(entry));
        }
        BigDecimal base = v.partida.multiply(BigDecimal.ONE.add(coef), MC);
        BigDecimal raw = base.multiply(internalRate, MC).subtract(credito, MC);
        BigDecimal up = raw.setScale(2, RoundingMode.HALF_UP).max(BigDecimal.ZERO);
        BigDecimal down = raw.setScale(2, RoundingMode.DOWN).max(BigDecimal.ZERO);
        memoria.add("ICMS = base %s × interna %s − crédito %s = %s".formatted(money(base), percent(internalRate), money(credito), money(up)));
        if (standard != Treatment.MERCADORIA) {
            memoria.add(baseDuplaLine(v, internalRate, credito, up));
        }
        return result(variables, item, standard, v.partida, up, down, up, reviewed, used, memoria, null);
    }

    /**
     * Information only, the result stays e-Fisco's: the base dupla of arts. 333/336 - (partida − ICMS destacado) ÷
     * (1 − interna) × interna − crédito - and how much more it would be, the exposure if the fisco charged it.
     */
    static String baseDuplaLine(Values v, BigDecimal internalRate, BigDecimal credito, BigDecimal efisco) {
        BigDecimal base = v.partida.subtract(v.icmsOrigem, MC).divide(BigDecimal.ONE.subtract(internalRate), MC);
        BigDecimal dupla = base.multiply(internalRate, MC).subtract(credito, MC).setScale(2, RoundingMode.HALF_UP).max(BigDecimal.ZERO);
        return "Informativo - base dupla (arts. 333/336): (%s − %s) ÷ (1 − %s) = %s; × %s − crédito %s = %s (exposição de %s; não muda o resultado)"
                .formatted(money(v.partida), money(v.icmsOrigem), plain(internalRate), money(base), percent(internalRate), money(credito),
                        money(dupla), money(dupla.subtract(efisco)));
    }

    /**
     * "Outras entradas" e-Fisco still charges: bonificação (x910) is goods for resale given for free, and x949 is
     * whatever the supplier did not name - the extract charges both, so they are not taken for a remessa.
     */
    static final Set<String> TAXED_OTHERS = Set.of("910", "949");

    /** Entry operations (after the 1/2/3 of the place) of goods the company keeps: ativo imobilizado, uso e consumo. */
    static final Set<String> USO_CONSUMO_OPERATIONS = Set.of("551", "552", "553", "555", "556", "557", "406", "407");

    /**
     * The credit e-Fisco takes (CreditoUtilizado): from a Simples supplier with no ICMS destacado, the interstate rate
     * over the product (crédito outorgado); otherwise the ICMS destacado, limited to the interstate rate over the
     * product (CFLA). Over the product and IPI, never over the freight - the freight's own ICMS (CT-e) isn't in the note.
     */
    static BigDecimal credit(Values v, BigDecimal inter) {
        BigDecimal limit = v.valorProduto.multiply(inter, MC);
        if (v.supplierSimples && v.icmsOrigem.signum() == 0) {
            return limit;
        }
        return v.icmsOrigem.min(limit);
    }

    private static String creditLine(Values v, BigDecimal inter, BigDecimal credito) {
        if (v.supplierSimples && v.icmsOrigem.signum() == 0) {
            return "Crédito outorgado (fornecedor do Simples): produto %s × interestadual %s = %s".formatted(money(v.valorProduto),
                    percent(inter), money(credito));
        }
        return credito.compareTo(v.icmsOrigem) < 0
                ? "Crédito: ICMS destacado %s limitado a produto × interestadual = %s".formatted(money(v.icmsOrigem), money(credito))
                : "Crédito: ICMS destacado %s".formatted(money(credito));
    }

    // ---------------------------------------------------------------- formulas

    /** ICMS-ST = partida × (1 + MVA ajustada) × interna − crédito; {@code adjustedTo4} rounds the MVA first (method C). */
    private static BigDecimal stValue(Values v, BigDecimal credito, BigDecimal mva, BigDecimal inter, BigDecimal interna,
                                      UnaryOperator<BigDecimal> round, boolean adjustedTo4) {
        BigDecimal adjusted = mvaAjustada(mva, inter, interna, adjustedTo4 ? r4() : UnaryOperator.identity());
        BigDecimal base = round.apply(v.partida.multiply(BigDecimal.ONE.add(adjusted), MC));
        BigDecimal tax = round.apply(base.multiply(interna, MC));
        return tax.subtract(credito).max(BigDecimal.ZERO);
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
                .filter(rule -> blank(rule.getProductCode()) || codigo.equalsIgnoreCase(rule.getProductCode().strip()))
                .filter(rule -> blank(rule.getSupplierCnpj()) || supplier.equals(digits(rule.getSupplierCnpj())))
                .max(Comparator.comparingInt(FronteiraCalculator::specificity)
                        .thenComparing(rule -> rule.getCreatedDate() == null ? java.time.LocalDateTime.MIN : rule.getCreatedDate()));
    }

    private static int specificity(FronteiraRule rule) {
        int score = 0;
        if (!blank(rule.getProductCode())) score += 1000;
        if (!blank(rule.getSupplierCnpj())) score += 500;
        if (!blank(rule.getCest())) score += 100;
        if (!blank(rule.getNcm())) score += 10 + digits(rule.getNcm()).length();
        if (rule.getCompany() != null) score += 1;
        return score;
    }

    /**
     * The mercadoria com agregação of the item: matched like the other rules (product, supplier, CEST, NCM), and
     * also by the origin region (blank = any) and whether the supplier is of the Simples (blank = any).
     */
    private static Optional<FronteiraRule> agregacaoRule(List<FronteiraRule> rules, FiscalXmlDocument document, FiscalXmlDocumentItem item,
                                                         LocalDate day, boolean supplierSimples) {
        String uf = document.getEmitenteUf() == null ? "" : document.getEmitenteUf().strip().toUpperCase(Locale.ROOT);
        String region = SUL_SUDESTE_UFS.contains(uf) ? SUL_SUDESTE : DEMAIS_REGIOES;
        return bestMatch(rules.stream()
                .filter(rule -> rule.getKind() == FronteiraRuleKind.AGREGACAO)
                .filter(rule -> blank(rule.getOriginRegion()) || rule.getOriginRegion().equals(region))
                .filter(rule -> rule.getSimplesSupplier() == null || rule.getSimplesSupplier() == supplierSimples)
                .toList(), FronteiraRuleKind.AGREGACAO, document, item, day);
    }

    /**
     * The carga líquida of the item, as e-Fisco's "tipo de cálculo": the rule whose description is the
     * company's credenciamento (from the extract) applies whatever the company's regime - it is the
     * credenciamento that gives it; a Simples company with no credenciamento match takes the rule
     * without description (art. 363-A), or the only one there is.
     */
    private static Optional<FronteiraRule> cargaLiquidaRule(Context context, FiscalXmlDocument document, FiscalXmlDocumentItem item,
                                                            LocalDate day) {
        List<FronteiraRule> candidates = context.rules().stream()
                .filter(rule -> rule.getKind() == FronteiraRuleKind.SIMPLES_PERCENTUAL && rule.isEnabled() && !rule.isDeleted() && rule.validOn(day))
                .toList();
        if (context.credenciamento() != null) {
            String wanted = plainText(context.credenciamento());
            List<FronteiraRule> named = candidates.stream()
                    .filter(rule -> !blank(rule.getDescription()) && plainText(rule.getDescription()).equals(wanted)).toList();
            Optional<FronteiraRule> match = bestMatch(named, FronteiraRuleKind.SIMPLES_PERCENTUAL, document, item, day);
            if (match.isPresent()) {
                return match;
            }
        }
        if (!context.simplesCompany()) {
            return Optional.empty();
        }
        List<FronteiraRule> unnamed = candidates.stream().filter(rule -> blank(rule.getDescription())).toList();
        return bestMatch(unnamed.isEmpty() && candidates.size() == 1 ? candidates : unnamed,
                FronteiraRuleKind.SIMPLES_PERCENTUAL, document, item, day);
    }

    // ---------------------------------------------------------------- values

    /**
     * @param creditoSn the credit a Simples supplier passes on (vCredICMSSN), when the note has it - for formulas
     */
    private record Values(BigDecimal valorProduto, BigDecimal ipi, BigDecimal frete, BigDecimal despesas, BigDecimal partida,
                          BigDecimal icmsOrigem, BigDecimal stRetido, BigDecimal creditoSn, BigDecimal credito,
                          boolean supplierSimples, boolean creditUnknown) {
    }

    /** First parser version that stores the Simples supplier's credit (pCredSN / vCredICMSSN). */
    static final int SIMPLES_CREDIT_VERSION = 6;
    private static final Set<String> SIMPLES_CRT = Set.of("1", "2", "4");

    /**
     * The note's freight, insurance and other expenses go to the items by product value, kept unrounded until the
     * final value (e-Fisco: FreteProporcionalItem = FreteFOB × item ÷ produtos); the discount doesn't reduce.
     */
    private static Values values(FiscalXmlDocument document, FiscalXmlDocumentItem item) {
        BigDecimal produto = zero(item.getValorTotal());
        BigDecimal ipi = zero(item.getValorIpi());
        BigDecimal products = zero(document.getValorProdutos());
        BigDecimal frete = share(zero(document.getValorFrete()), produto, products);
        BigDecimal outras = share(zero(document.getValorSeguro()).add(zero(document.getValorOutrasDespesas())), produto, products);
        BigDecimal despesas = frete.add(outras);
        FiscalItemTaxDetail detail = item.getTaxDetail();
        BigDecimal st = detail == null ? BigDecimal.ZERO : zero(detail.valorIcmsSt());
        BigDecimal icmsOrigem = zero(item.getValorIcms());
        BigDecimal creditoSn = detail == null ? null : detail.valorCreditoSn();
        String crt = document.getEmitenteRegimeTributario() == null ? "" : document.getEmitenteRegimeTributario().strip();
        boolean supplierSimples = (detail != null && detail.csosn() != null && !detail.csosn().isBlank()) || SIMPLES_CRT.contains(crt);
        boolean readCredit = document.getMetadataVersion() != null && document.getMetadataVersion() >= SIMPLES_CREDIT_VERSION;
        boolean creditUnknown = supplierSimples && icmsOrigem.signum() == 0 && creditoSn == null && !readCredit;
        BigDecimal credito = icmsOrigem.signum() > 0 ? icmsOrigem : zero(creditoSn);
        return new Values(produto, ipi, frete, despesas, produto.add(ipi).add(despesas), icmsOrigem, st, creditoSn, credito,
                supplierSimples, creditUnknown);
    }

    /** The item's part of a note-level value, by its product value. */
    private static BigDecimal share(BigDecimal total, BigDecimal produto, BigDecimal products) {
        return total.signum() == 0 || products.signum() == 0 ? BigDecimal.ZERO : total.multiply(produto, MC).divide(products, MC);
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

    private static ItemResult result(Map<String, BigDecimal> variables, FiscalXmlDocumentItem item, Treatment treatment, BigDecimal partida,
                                     BigDecimal value, BigDecimal min, BigDecimal max, boolean reviewed, List<UUID> used,
                                     List<String> memoria, String motivo) {
        return new ItemResult(item.getNumeroItem() == null ? 0 : item.getNumeroItem(), item.getCodigo(), item.getDescricao(), item.getNcm(),
                item.getCfop(), treatment, partida, value, min, max, reviewed, List.copyOf(used), List.copyOf(memoria), motivo,
                java.util.Collections.unmodifiableMap(new LinkedHashMap<>(variables)));
    }

    private static ItemResult semRegra(Map<String, BigDecimal> variables, FiscalXmlDocumentItem item, BigDecimal partida, List<UUID> used,
                                       List<String> memoria, String motivo) {
        memoria.add("Sem regra: " + motivo);
        return result(variables, item, Treatment.SEM_REGRA, partida, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false, used,
                memoria, motivo);
    }

    private static String scope(FronteiraRule rule) {
        List<String> parts = new ArrayList<>();
        if (!blank(rule.getProductCode())) parts.add("produto " + rule.getProductCode());
        if (!blank(rule.getSupplierCnpj())) parts.add("fornecedor " + rule.getSupplierCnpj());
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

    static String plainText(String value) {
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
