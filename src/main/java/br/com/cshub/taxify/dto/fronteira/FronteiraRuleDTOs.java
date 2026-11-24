package br.com.cshub.taxify.dto.fronteira;

import br.com.cshub.taxify.enumerated.FronteiraRuleKind;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Fronteira PE: the ICMS table of the standard calculation and the accountant's own formulas. */
public final class FronteiraRuleDTOs {

    private FronteiraRuleDTOs() {
    }

    /** Rates in percent (20.5 = 20,5%); {@code companyId} null = every company. */
    public record Rule(UUID id, UUID companyId, FronteiraRuleKind kind, String descricao, String ncm, String cest,
                       String codigoProduto, String cnpjFornecedor, BigDecimal aliquota, BigDecimal mva, BigDecimal percentual,
                       BigDecimal agregacao, String regiaoOrigem, Boolean fornecedorSimples, String formula, String aplicaEm, String fundamento, String link, LocalDate validFrom, LocalDate validUntil,
                       boolean enabled, boolean reviewed, String reviewedBy, LocalDateTime reviewedAt,
                       String createdBy, LocalDateTime createdDate) {
    }

    /** What the screen sends to create or change a rule. */
    public record RuleInput(UUID companyId, FronteiraRuleKind kind, String descricao, String ncm, String cest,
                            String codigoProduto, String cnpjFornecedor, BigDecimal aliquota, BigDecimal mva,
                            BigDecimal percentual, BigDecimal agregacao, String regiaoOrigem, Boolean fornecedorSimples,
                            String formula, String aplicaEm, String fundamento, String link,
                            LocalDate validFrom, LocalDate validUntil) {
    }

    /**
     * A formula checked before saving: {@code ok} false comes with the message. {@code resultado} is the
     * formula's value with {@code valores} - the note item's own values when they were sent, otherwise
     * sample ones ({@code exemplo} true).
     */
    public record FormulaCheck(boolean ok, String message, Map<String, String> variables, BigDecimal resultado,
                               Map<String, BigDecimal> valores, boolean exemplo) {
    }

    /** One item's calculation, with its memory. */
    public record ItemCalc(int numeroItem, String codigo, String descricao, String ncm, String cfop, String tratamento,
                           String tratamentoLabel, BigDecimal partida, BigDecimal valor, BigDecimal minimo, BigDecimal maximo,
                           boolean conferido, List<UUID> regras, List<String> memoria, String semRegraMotivo,
                           Map<String, BigDecimal> variaveis) {
    }

    /**
     * Taxify's ICMS antecipado of a note: {@code valor} by the method expected to be SEFAZ's, {@code minimo}/
     * {@code maximo} the range over the rounding methods. {@code conferido} false when a rule used is still
     * "a conferir"; {@code semRegra} when some item has no rule (its value is left out).
     */
    public record NoteCalc(BigDecimal valor, BigDecimal minimo, BigDecimal maximo, boolean conferido, boolean semRegra,
                           List<ItemCalc> itens) {
    }
}
