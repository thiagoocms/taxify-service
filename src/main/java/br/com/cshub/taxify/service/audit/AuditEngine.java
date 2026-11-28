package br.com.cshub.taxify.service.audit;

import br.com.cshub.taxify.common.IssuePeriod;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.pendency.Pendency;
import br.com.cshub.taxify.domain.fiscaldocument.GoodsInvoice;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentItem;
import br.com.cshub.taxify.domain.xml.SefazNoteSummary;
import br.com.cshub.taxify.dto.assessment.AssessmentAlertDTO;
import br.com.cshub.taxify.dto.assessment.MonthRevenueDTO;
import br.com.cshub.taxify.dto.assessment.TaxAssessmentDTO;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlDom;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlDomParser;
import br.com.cshub.taxify.integration.sieg.FiscalXmlFileStorage;
import br.com.cshub.taxify.repository.FiscalDocumentRepository;
import br.com.cshub.taxify.repository.GoodsInvoiceRepository;
import br.com.cshub.taxify.repository.SefazNoteSummaryRepository;
import br.com.cshub.taxify.service.assessment.TaxAssessmentService;
import br.com.cshub.taxify.service.fiscalcheck.FiscalCheckContext;
import br.com.cshub.taxify.service.fiscalcheck.FiscalCheckEngine;
import br.com.cshub.taxify.service.fiscalcheck.FiscalDivergence;
import br.com.cshub.taxify.service.fiscalcheck.SubstitutionCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Runs the Auditoria tipo malha (roadmap M9) over one company and competence - the cross-checks
 * the state tax authority uses, found before an inspection does:
 * <ul>
 *   <li>integrity of the company's own notes: numbering gaps and repeated numbers per series,
 *       returns without the original note, entries left only with SEFAZ's summary;</li>
 *   <li>coherence of the items of the notes it issued - the item checks of the divergences
 *       ({@link FiscalCheckEngine}): CFOP x destination, interstate rate (4% imported), CRT x CST,
 *       ST CFOP x CST, CEST on ST items, the IBS/CBS group of 2026;</li>
 *   <li>anomalies: revenue far above the last 12 months, NFC-e cancellations above 5%, RBT12 near
 *       the Simples sublimit / ceiling (the assessment's own alerts).</li>
 * </ul>
 * Pure reading - {@link AuditService} turns the findings into pendencies.
 */
@Component
public class AuditEngine {

    private static final Logger log = LoggerFactory.getLogger(AuditEngine.class);
    /** Evidence listed per finding - the rest is in the count. */
    static final int MAX_EVIDENCE = 15;
    private static final int MAX_RETURNS_READ = 100;
    private static final int SUMMARY_GRACE_DAYS = 15;
    private static final BigDecimal SPIKE_FACTOR = new BigDecimal("3");
    private static final int MIN_HISTORY_MONTHS = 3;
    private static final BigDecimal NFCE_CANCEL_RATE = new BigDecimal("0.05");
    private static final int NFCE_MIN_NOTES = 20;
    private static final String NFE_NS = "http://www.portalfiscal.inf.br/nfe";

    /** Divergence code of the item/document checks -> the audit rule it feeds. */
    static final Map<String, AuditRuleDefinition> CHECK_RULES = Map.of(
            "CFOP_DESTINATION", AuditRuleDefinition.CFOP_DESTINO,
            "INTERSTATE_RATE", AuditRuleDefinition.ALIQUOTA_INTERESTADUAL,
            "CRT_CST", AuditRuleDefinition.CRT_CST,
            SubstitutionCheck.CODE_CFOP, AuditRuleDefinition.ST_CFOP_CST,
            SubstitutionCheck.CODE_CEST, AuditRuleDefinition.ST_SEM_CEST,
            "IBSCBS_MISSING", AuditRuleDefinition.IBSCBS_GRUPO);

    private static final Map<String, AuditRuleDefinition> ASSESSMENT_RULES = Map.of(
            "SIMPLES_NEAR_SUBLIMIT", AuditRuleDefinition.RBT12_SUBLIMITE,
            "SIMPLES_SUBLIMIT", AuditRuleDefinition.RBT12_SUBLIMITE,
            "SIMPLES_CEILING", AuditRuleDefinition.RBT12_LIMITE,
            "MEI_NEAR_LIMIT", AuditRuleDefinition.RBT12_LIMITE,
            "MEI_EXCESS", AuditRuleDefinition.RBT12_LIMITE,
            "MEI_EXCLUSION", AuditRuleDefinition.RBT12_LIMITE);

    /** One indication found. {@code keySuffix} tells apart several findings of the same rule in the month. */
    public record Finding(AuditRuleDefinition rule, String keySuffix, String objectType, String objectId, String title,
                          String detail) {
    }

    public record Result(List<Finding> findings, int notesChecked) {
    }

    private final GoodsInvoiceRepository documentRepository;
    private final FiscalDocumentRepository anyDocumentRepository;
    private final SefazNoteSummaryRepository summaryRepository;
    private final FiscalCheckEngine checkEngine;
    private final TaxAssessmentService assessmentService;
    private final FiscalXmlFileStorage fileStorage;

    public AuditEngine(GoodsInvoiceRepository documentRepository, FiscalDocumentRepository anyDocumentRepository,
                       SefazNoteSummaryRepository summaryRepository, FiscalCheckEngine checkEngine,
                       TaxAssessmentService assessmentService, FiscalXmlFileStorage fileStorage) {
        this.documentRepository = documentRepository;
        this.anyDocumentRepository = anyDocumentRepository;
        this.summaryRepository = summaryRepository;
        this.checkEngine = checkEngine;
        this.assessmentService = assessmentService;
        this.fileStorage = fileStorage;
    }

    @Transactional(readOnly = true)
    public Result audit(Company company, YearMonth month) {
        List<GoodsInvoice> notes = documentRepository.findIssuedBetween(
                company.getId(), IssuePeriod.monthStart(month), IssuePeriod.monthEndExclusive(month));
        List<GoodsInvoice> issued = notes.stream().filter(note -> !note.isDeleted() && note.isOwnIssue()).toList();
        List<Finding> findings = new ArrayList<>();
        numbering(issued, findings);
        returns(company, issued, findings);
        summaries(company, month, findings);
        coherence(company, issued, findings);
        anomalies(company, month, issued, findings);
        return new Result(findings, notes.size());
    }

    // ---------------------------------------------------------------- integrity

    /** Per model and series of the company's own notes: repeated numbers and gaps between the month's numbers. */
    void numbering(List<GoodsInvoice> issued, List<Finding> findings) {
        Map<String, List<GoodsInvoice>> bySeries = issued.stream()
                .filter(note -> number(note) != null)
                .collect(Collectors.groupingBy(note -> modelOf(note) + "|" + (note.getSeries() == null ? "0" : note.getSeries().strip()),
                        TreeMap::new, Collectors.toList()));
        for (Map.Entry<String, List<GoodsInvoice>> entry : bySeries.entrySet()) {
            String[] parts = entry.getKey().split("\\|");
            String label = ("65".equals(parts[0]) ? "NFC-e" : "NF-e") + " série " + parts[1];
            Map<Long, List<GoodsInvoice>> byNumber = entry.getValue().stream()
                    .collect(Collectors.groupingBy(AuditEngine::number, TreeMap::new, Collectors.toList()));
            for (Map.Entry<Long, List<GoodsInvoice>> same : byNumber.entrySet()) {
                Set<String> keys = same.getValue().stream().map(GoodsInvoice::getAccessKey)
                        .filter(key -> key != null).collect(Collectors.toCollection(LinkedHashSet::new));
                if (keys.size() > 1) {
                    findings.add(new Finding(AuditRuleDefinition.NUMERO_DUPLICADO, entry.getKey() + ":" + same.getKey(),
                            Pendency.OBJECT_NOTE, same.getValue().getFirst().getId().toString(),
                            label + ", nº " + same.getKey() + " com " + keys.size() + " chaves diferentes",
                            "Chaves: " + String.join(", ", keys) + "."));
                }
            }
            TreeSet<Long> numbers = new TreeSet<>(byNumber.keySet());
            List<Long> missing = new ArrayList<>();
            long count = 0;
            Long previous = null;
            for (Long current : numbers) {
                if (previous != null && current - previous > 1) {
                    count += current - previous - 1;
                    for (long gap = previous + 1; gap < current && missing.size() < MAX_EVIDENCE; gap++) {
                        missing.add(gap);
                    }
                }
                previous = current;
            }
            if (count > 0) {
                findings.add(new Finding(AuditRuleDefinition.NUMERACAO_LACUNA, entry.getKey(), Pendency.OBJECT_COMPANY, null,
                        label + ": " + count + " número(s) sem nota entre " + numbers.first() + " e " + numbers.last(),
                        "Faltam: " + missing.stream().map(String::valueOf).collect(Collectors.joining(", "))
                                + (count > missing.size() ? " e outros" : "")
                                + ". Confira se foram inutilizados na SEFAZ ou se as notas não chegaram ao Taxify."));
            }
        }
    }

    /** Returns (finNFe 4) the company issued: without a referenced note, or referencing one Taxify doesn't have. */
    void returns(Company company, List<GoodsInvoice> issued, List<Finding> findings) {
        int read = 0;
        for (GoodsInvoice note : issued) {
            if (!"4".equals(note.getHeader().purpose()) || note.isCanceled() || read >= MAX_RETURNS_READ) {
                continue;
            }
            read++;
            List<String> referenced = referencedKeys(note);
            if (referenced == null) {
                continue; // file unreadable - nothing to say
            }
            String label = "Devolução nº " + note.getNumber();
            if (referenced.isEmpty()) {
                findings.add(new Finding(AuditRuleDefinition.DEVOLUCAO_SEM_ORIGINAL, note.getId().toString(), Pendency.OBJECT_NOTE,
                        note.getId().toString(), label + " sem nota referenciada",
                        "A nota de devolução (finalidade 4) não informa a nota original (NFref)."));
                continue;
            }
            List<String> unknown = referenced.stream()
                    .filter(key -> !anyDocumentRepository.existsByCompany_IdAndAccessKey(company.getId(), key)).toList();
            if (unknown.size() == referenced.size()) {
                findings.add(new Finding(AuditRuleDefinition.DEVOLUCAO_SEM_ORIGINAL, note.getId().toString(), Pendency.OBJECT_NOTE,
                        note.getId().toString(), label + ": nota original não está no Taxify",
                        "Referencia " + String.join(", ", unknown) + " - confira se a nota original foi importada."));
            }
        }
    }

    /** refNFe of the note's XML; null when the file can't be read. */
    List<String> referencedKeys(GoodsInvoice note) {
        try {
            Document doc = FiscalXmlDomParser.parse(fileStorage.read(note.getXmlFilePath()));
            NodeList refs = doc.getElementsByTagNameNS(NFE_NS, "refNFe");
            if (refs.getLength() == 0) {
                refs = doc.getElementsByTagNameNS("*", "refNFe");
            }
            List<String> keys = new ArrayList<>();
            for (int i = 0; i < refs.getLength(); i++) {
                keys.add(refs.item(i).getTextContent().strip());
            }
            return keys;
        } catch (Exception e) {
            log.debug("Could not read the XML of note {} for the audit", note.getId(), e);
            return null;
        }
    }

    /** Entries of the month SEFAZ gave only as a summary, still without XML after the grace days. */
    void summaries(Company company, YearMonth month, List<Finding> findings) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(SUMMARY_GRACE_DAYS);
        List<SefazNoteSummary> waiting = summaryRepository.findByCompany_IdAndCompletedAtIsNullAndIssueDateGreaterThanEqualAndIssueDateLessThan(company.getId(),
                        IssuePeriod.monthStart(month), IssuePeriod.monthEndExclusive(month)).stream()
                .filter(summary -> summary.getCreatedDate() == null || summary.getCreatedDate().isBefore(cutoff))
                .filter(SefazNoteSummary::isAuthorized)
                .toList();
        if (!waiting.isEmpty()) {
            findings.add(new Finding(AuditRuleDefinition.NOTA_SO_RESUMO, "", Pendency.OBJECT_COMPANY, null,
                    waiting.size() + " entrada(s) do mês só com o resumo da SEFAZ há mais de " + SUMMARY_GRACE_DAYS + " dias",
                    "Sem o XML a nota fica fora da escrituração e das checagens. Exemplos: " + waiting.stream().limit(5)
                            .map(summary -> nameOr(summary.getIssuerName()) + " " + money(summary.getTotalValue()))
                            .collect(Collectors.joining("; ")) + ". Veja a tela de Manifestação."));
        }
    }

    // ---------------------------------------------------------------- coherence

    /** The item checks over the notes the company issued, one finding per rule with the notes as evidence. */
    void coherence(Company company, List<GoodsInvoice> issued, List<Finding> findings) {
        if (issued.isEmpty()) {
            return;
        }
        FiscalCheckContext context = checkEngine.contextFor(company.getId());
        Map<AuditRuleDefinition, Map<GoodsInvoice, String>> byRule = new LinkedHashMap<>();
        for (GoodsInvoice note : issued) {
            if (!FiscalCheckEngine.isCheckable(note)) {
                continue;
            }
            List<FiscalDocumentItem> items = note.getItems();
            List<FiscalDivergence> divergences = new ArrayList<>(checkEngine.checkDocument(note, items, context));
            for (FiscalDocumentItem item : items) {
                divergences.addAll(checkEngine.check(item, context));
            }
            for (FiscalDivergence divergence : divergences) {
                AuditRuleDefinition rule = CHECK_RULES.get(divergence.code());
                if (rule != null) {
                    byRule.computeIfAbsent(rule, key -> new LinkedHashMap<>()).putIfAbsent(note, divergence.message());
                }
            }
        }
        for (Map.Entry<AuditRuleDefinition, Map<GoodsInvoice, String>> entry : byRule.entrySet()) {
            Map<GoodsInvoice, String> notes = entry.getValue();
            GoodsInvoice first = notes.keySet().iterator().next();
            String evidence = notes.keySet().stream().limit(MAX_EVIDENCE).map(note -> "nº " + note.getNumber())
                    .collect(Collectors.joining(", "));
            findings.add(new Finding(entry.getKey(), "", notes.size() == 1 ? Pendency.OBJECT_NOTE : Pendency.OBJECT_COMPANY,
                    notes.size() == 1 ? first.getId().toString() : null,
                    notes.size() + " nota(s) emitida(s): " + entry.getKey().title.toLowerCase(),
                    "Ex.: " + notes.get(first) + ". Notas: " + evidence + (notes.size() > MAX_EVIDENCE ? " e outras" : "")
                            + ". Detalhe por item na aba Divergências de cada nota."));
        }
    }

    // ---------------------------------------------------------------- anomalies

    void anomalies(Company company, YearMonth month, List<GoodsInvoice> issued, List<Finding> findings) {
        nfceCancellations(issued, findings);
        TaxAssessmentDTO assessment;
        try {
            assessment = assessmentService.assess(company.getId(), month.toString());
        } catch (RuntimeException e) {
            log.warn("Assessment unavailable for the audit of company {} ({})", company.getId(), month, e);
            return;
        }
        revenueSpike(month, assessment, findings);
        Set<AuditRuleDefinition> limits = new java.util.HashSet<>();
        for (AssessmentAlertDTO alert : assessment.alerts() == null ? List.<AssessmentAlertDTO>of() : assessment.alerts()) {
            AuditRuleDefinition rule = ASSESSMENT_RULES.get(alert.code());
            if (rule != null && limits.add(rule)) {
                findings.add(new Finding(rule, "", Pendency.OBJECT_COMPANY, null, alert.message(),
                        "RBT12 " + money(assessment.rbt12()) + " (Apuração do mês)."));
            }
        }
    }

    private void nfceCancellations(List<GoodsInvoice> issued, List<Finding> findings) {
        List<GoodsInvoice> nfce = issued.stream().filter(note -> "65".equals(modelOf(note))).toList();
        long canceled = nfce.stream().filter(GoodsInvoice::isCanceled).count();
        if (nfce.size() < NFCE_MIN_NOTES || canceled == 0) {
            return;
        }
        BigDecimal rate = BigDecimal.valueOf(canceled).divide(BigDecimal.valueOf(nfce.size()), 4, RoundingMode.HALF_UP);
        if (rate.compareTo(NFCE_CANCEL_RATE) > 0) {
            findings.add(new Finding(AuditRuleDefinition.NFCE_CANCELAMENTO, "", Pendency.OBJECT_COMPANY, null,
                    canceled + " de " + nfce.size() + " NFC-e canceladas no mês (" + percent(rate) + ")",
                    "Cancelamento acima de 5% costuma chamar a atenção da fiscalização - confira os motivos."));
        }
    }

    private void revenueSpike(YearMonth month, TaxAssessmentDTO assessment, List<Finding> findings) {
        List<MonthRevenueDTO> history = assessment.history() == null ? List.of() : assessment.history();
        BigDecimal current = assessment.revenue() == null ? null : assessment.revenue().net();
        List<BigDecimal> previous = history.stream()
                .filter(item -> !month.toString().equals(item.month()))
                .map(MonthRevenueDTO::net)
                .filter(value -> value != null && value.signum() > 0)
                .toList();
        if (current == null || current.signum() <= 0 || previous.size() < MIN_HISTORY_MONTHS) {
            return;
        }
        BigDecimal average = previous.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(previous.size()), 2, RoundingMode.HALF_UP);
        if (current.compareTo(average.multiply(SPIKE_FACTOR)) > 0) {
            findings.add(new Finding(AuditRuleDefinition.FATURAMENTO_ATIPICO, "", Pendency.OBJECT_COMPANY, null,
                    "Faturamento de " + money(current) + " - " + current.divide(average, 1, RoundingMode.HALF_UP)
                            + " vezes a média dos meses anteriores",
                    "Média de " + previous.size() + " mês(es) com nota: " + money(average)
                            + ". Variação brusca de faturamento é cruzamento comum da malha - confira a origem."));
        }
    }

    // ---------------------------------------------------------------- helpers

    private static Long number(GoodsInvoice note) {
        try {
            return note.getNumber() == null ? null : Long.valueOf(note.getNumber().strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String modelOf(GoodsInvoice note) {
        return note.getModel() == null ? "55" : note.getModel().strip();
    }

    private static String nameOr(String name) {
        return name == null || name.isBlank() ? "emitente não informado" : name;
    }

    static String money(BigDecimal value) {
        return value == null ? "-" : java.text.NumberFormat.getCurrencyInstance(java.util.Locale.of("pt", "BR")).format(value);
    }

    private static String percent(BigDecimal rate) {
        return rate.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP).toPlainString().replace('.', ',') + "%";
    }

    /** For the tests: rules fed by the item checks. */
    static Map<String, AuditRuleDefinition> checkRules() {
        return new HashMap<>(CHECK_RULES);
    }
}
