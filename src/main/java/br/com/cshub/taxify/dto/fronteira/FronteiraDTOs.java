package br.com.cshub.taxify.dto.fronteira;

import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlSummaryDTO.DivergenceSummary;
import br.com.cshub.taxify.dto.fronteira.FronteiraRuleDTOs.NoteCalc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Fronteira PE: the e-Fisco extract of a period next to Taxify's own calculation of each note. */
public final class FronteiraDTOs {

    private FronteiraDTOs() {
    }

    /** What else a note of the extract got flagged with (besides the result of the calculation). */
    public enum Flag {
        /** The extract charges a note Taxify doesn't have (look for it in SIEG / SEFAZ). */
        SEM_XML,
        /** Taxify only has SEFAZ's summary of the note - the full XML hasn't come yet. */
        SO_RESUMO,
        /** Charged at a rate other than the extract's usual one (the credenciamento's) - a hint, to be explained. */
        FORA_DA_TAXA,
        /** The extract's note value differs from the note's total in the XML. */
        VALOR_DIFERENTE,
        /** The note was canceled, but is still charged. */
        NOTA_CANCELADA,
        /** The fiscal checks found something on the note (NCM, CFOP, CST...). */
        DIVERGENCIA_FISCAL
    }

    /** Taxify's calculation against what e-Fisco charged. */
    public enum Result {
        /** The charge falls in the range of Taxify's rounding methods (with the tolerance). */
        CONFERE,
        COBROU_A_MAIS,
        COBROU_A_MENOS,
        /** e-Fisco charged nothing where Taxify calculates ICMS. */
        NAO_CALCULOU,
        /** Some item has no rule in the table - never "cobrou a mais". */
        SEM_REGRA,
        /** Without the XML (or only the summary) there is nothing to calculate yet. */
        PENDENTE
    }

    public record Period(String periodo, String numeroExtrato, BigDecimal icmsDevido, int notas, LocalDateTime importedAt) {
    }

    public record Extract(String periodo, String numeroExtrato, String inscricaoEstadual, String razaoSocial, String are,
                          String codigoReceita, String credenciamentoAntecipacao, String credenciamentoInterno,
                          LocalDate dataVencimento, LocalDateTime emitidoEm,
                          BigDecimal icmsARecolher, BigDecimal icmsPago, BigDecimal icmsEmProcesso, BigDecimal icmsAutuado,
                          BigDecimal icmsMandadoSeguranca, BigDecimal icmsAnistiado, BigDecimal icmsRegDebito,
                          String fileName, LocalDateTime importedAt, String importedBy,
                          /** The PDF is kept: "Ver PDF" opens it. */
                          boolean temPdf) {
    }

    /**
     * @param taxaPredominante the rate (% of the note value) most notes were charged at; null with too few notes
     * @param icmsCalculado    Taxify's total over the notes it could calculate
     * @param cobradoAMais     sum of what e-Fisco charged beyond Taxify's calculation (COBROU_A_MAIS)
     * @param cobradoAMenos    sum of what e-Fisco charged below it (COBROU_A_MENOS and NAO_CALCULOU) - a risk, not a saving
     * @param aConferir        notes whose result used a rule nobody has checked yet
     * @param valorGuia        the guide after the adjustments: what e-Fisco charged minus what it charged beyond Taxify's
     *                         calculation (to be asked in revision) - the notes charged below stay as charged (a risk)
     */
    public record Summary(int notas, BigDecimal valorNotas, BigDecimal icmsDevido, BigDecimal taxaPredominante,
                          int foraDaTaxa, BigDecimal icmsForaDaTaxa, BigDecimal diferencaForaDaTaxa,
                          int semXml, int valorDiferente, int canceladas, int comDivergencia, int semCobranca,
                          BigDecimal icmsCalculado, BigDecimal cobradoAMais, BigDecimal cobradoAMenos,
                          int confere, int aMais, int aMenos, int naoCalculou, int semRegra, int pendentes, int aConferir,
                          BigDecimal valorGuia) {
    }

    /** The Taxify note behind a line of the extract; {@code chaveAcesso} the NF-e's 44-digit key. */
    public record NoteRef(UUID documentId, String emitenteNome, LocalDateTime issueDate, BigDecimal valorTotalNota,
                          String naturezaOperacao, List<String> cfops, boolean cancelada, DivergenceSummary divergencias,
                          String chaveAcesso) {
    }

    /**
     * @param taxa              ICMS devido / valor da NF, in % (4 places)
     * @param diferencaParaTaxa ICMS devido minus valor × taxa predominante - only on FORA_DA_TAXA
     * @param diferenca         ICMS devido minus Taxify's value (method C); null without a calculation
     */
    public record Line(int linha, String registro, String dataPassagem, String numeroNota, String uf, String cnpjEmitente,
                       BigDecimal valorNota, BigDecimal icmsDevido, BigDecimal taxa, BigDecimal diferencaParaTaxa,
                       List<Flag> flags, NoteRef nota, Result resultado, boolean conferido, BigDecimal diferenca,
                       NoteCalc calculo) {
    }

    /** An interstate entry issued in the period that the extract doesn't charge - with Taxify's prévia. */
    public record Uncharged(UUID documentId, String numero, String emitenteNome, String emitenteCnpj, String emitenteUf,
                            LocalDateTime issueDate, BigDecimal valorTotalNota, List<String> cfops, NoteCalc calculo) {
    }

    /**
     * {@code extract} null: no extract imported for the period yet - the lines are empty, and
     * {@code semCobranca} holds the month's interstate entries with the prévia ({@code previaIcms}).
     * {@code cfopFilter}: with {@code cfops} in the request, which lines and entries match and their totals - everything
     * else stays of the whole extract (CF-RN-24); null without {@code cfops}.
     */
    public record Report(UUID companyId, String companyName, String periodo, Extract extract, Summary summary,
                         List<Line> lines, List<Uncharged> semCobranca, BigDecimal previaIcms, List<Period> periodos,
                         CfopSelection cfopFilter) {

        /** Without a CFOP filter. */
        public Report(UUID companyId, String companyName, String periodo, Extract extract, Summary summary,
                      List<Line> lines, List<Uncharged> semCobranca, BigDecimal previaIcms, List<Period> periodos) {
            this(companyId, companyName, periodo, extract, summary, lines, semCobranca, previaIcms, periodos, null);
        }

        public Report withCfopFilter(CfopSelection selection) {
            return new Report(companyId, companyName, periodo, extract, summary, lines, semCobranca, previaIcms, periodos, selection);
        }
    }

    /**
     * The CFOP cut of the report (spec 2026-11-27, CF-RN-20 to 27): a line matches when its note has at least one of the
     * CFOPs the supplier issued (the ones the line shows, {@code NoteRef.cfops}).
     *
     * @param cfops             the canonical list (normalized, no duplicate, ascending)
     * @param matchingLines     {@code Line.linha} of each matching line, ascending
     * @param matchingUncharged {@code documentId} of each matching entry without a charge
     * @param linesWithoutXml   lines of the extract without a note here (SEM_XML, SO_RESUMO): they never match (CF-RN-22)
     */
    public record CfopSelection(List<String> cfops, List<Integer> matchingLines, List<UUID> matchingUncharged,
                                int linesWithoutXml, CfopSelectionTotals totals) {
    }

    /**
     * CF-RN-25: the same accounts as the {@link Summary}, over the matching lines only - the whole note, as e-Fisco charges.
     *
     * @param notes            matching lines
     * @param invoicesAmount   sum of their {@code valorNota} (the extract's)
     * @param icmsCharged      sum of their {@code icmsDevido}
     * @param icmsCalculated   Taxify's calculation of them (without the SEM_REGRA ones)
     * @param chargedAbove     COBROU_A_MAIS differences
     * @param chargedBelow     COBROU_A_MENOS and NAO_CALCULOU differences
     * @param unchargedCount   matching entries without a charge
     * @param unchargedPreview their prévia
     */
    public record CfopSelectionTotals(int notes, BigDecimal invoicesAmount, BigDecimal icmsCharged, BigDecimal icmsCalculated,
                                      BigDecimal chargedAbove, BigDecimal chargedBelow, int unchargedCount,
                                      BigDecimal unchargedPreview) {
    }
}
