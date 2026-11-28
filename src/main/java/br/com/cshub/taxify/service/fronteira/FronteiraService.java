package br.com.cshub.taxify.service.fronteira;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.company.StateRegistration;
import br.com.cshub.taxify.domain.fronteira.EfiscoExtract;
import br.com.cshub.taxify.domain.fronteira.EfiscoExtractNote;
import br.com.cshub.taxify.domain.fiscaldocument.GoodsInvoice;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlSummaryDTO.DivergenceSummary;
import br.com.cshub.taxify.dto.fronteira.FronteiraDTOs;
import br.com.cshub.taxify.dto.fronteira.FronteiraDTOs.Flag;
import br.com.cshub.taxify.dto.fronteira.FronteiraDTOs.Result;
import br.com.cshub.taxify.dto.fronteira.FronteiraRuleDTOs;
import br.com.cshub.taxify.domain.xml.SefazNoteSummary;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocumentItem;
import br.com.cshub.taxify.enumerated.TaxRegime;
import br.com.cshub.taxify.repository.SefazNoteSummaryRepository;
import org.springframework.data.domain.Pageable;
import br.com.cshub.taxify.enumerated.FiscalDirection;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.exception.ConflictException;
import br.com.cshub.taxify.repository.EfiscoExtractRepository;
import br.com.cshub.taxify.repository.FiscalDocumentItemRepository;
import br.com.cshub.taxify.repository.GoodsInvoiceRepository;
import br.com.cshub.taxify.service.fiscalcheck.DocumentDivergenceService;
import br.com.cshub.taxify.validation.CfopFilterValidation;
import br.com.cshub.taxify.validation.CompanyValidation;
import br.com.cshub.taxify.domain.fiscaldocument.CfopFilter;
import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Fronteira PE: imports the e-Fisco extract of the ICMS antecipado and sets each charged note next to
 * Taxify's own calculation of it ({@link FronteiraCalculator}, with the table and the accountant's formulas
 * of {@link FronteiraRuleService}): confere, cobrou a mais, a menos, não calculou, sem regra or pendente.
 * Also flags notes charged without an XML here (or with only SEFAZ's summary), values that don't match the
 * XML, canceled notes still charged, the notes off the extract's usual rate, and gives the prévia of the
 * month's interstate entries the extract doesn't charge yet.
 */
@Service
public class FronteiraService {

    static final String UF = "PE";
    /** Off the predominant rate by more than this (percentage points)... */
    static final BigDecimal RATE_TOLERANCE = new BigDecimal("0.15");
    /** ...and by more than this in money: small notes round to rates far off without being so. */
    static final BigDecimal MONEY_TOLERANCE = new BigDecimal("0.05");
    /** Fewer notes than this can't tell a predominant rate. */
    static final int MIN_NOTES_FOR_RATE = 3;

    private static final String SYSTEM = "sistema";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** Who "imported" an extract the e-Fisco sync fetched. */
    public static final String EFISCO_IMPORTER = "e-Fisco (automático)";

    private final EfiscoExtractParser parser;
    private final EfiscoExtractRepository extractRepository;
    private final GoodsInvoiceRepository documentRepository;
    private final FiscalDocumentItemRepository itemRepository;
    private final DocumentDivergenceService divergenceService;
    private final FronteiraRuleService ruleService;
    private final SefazNoteSummaryRepository summaryRepository;
    private final br.com.cshub.taxify.repository.ProductFiscalRuleRepository productRuleRepository;
    private final CompanyValidation companyValidation;
    private final MessageService messageService;
    private final AuditorAware<String> auditorAware;
    private final br.com.cshub.taxify.integration.sieg.FiscalXmlFileStorage storage;
    private final CfopFilterValidation cfopFilterValidation;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FronteiraService.class);

    public FronteiraService(EfiscoExtractParser parser, EfiscoExtractRepository extractRepository,
                            GoodsInvoiceRepository documentRepository, FiscalDocumentItemRepository itemRepository,
                            DocumentDivergenceService divergenceService, FronteiraRuleService ruleService,
                            SefazNoteSummaryRepository summaryRepository,
                            br.com.cshub.taxify.repository.ProductFiscalRuleRepository productRuleRepository,
                            CompanyValidation companyValidation, MessageService messageService, AuditorAware<String> auditorAware,
                            br.com.cshub.taxify.integration.sieg.FiscalXmlFileStorage storage,
                            CfopFilterValidation cfopFilterValidation) {
        this.storage = storage;
        this.cfopFilterValidation = cfopFilterValidation;
        this.productRuleRepository = productRuleRepository;
        this.ruleService = ruleService;
        this.summaryRepository = summaryRepository;
        this.parser = parser;
        this.extractRepository = extractRepository;
        this.documentRepository = documentRepository;
        this.itemRepository = itemRepository;
        this.divergenceService = divergenceService;
        this.companyValidation = companyValidation;
        this.messageService = messageService;
        this.auditorAware = auditorAware;
    }

    // ---------------------------------------------------------------- import

    /** Reads the PDF and stores it as the company's extract of its period (replacing an earlier import). */
    @Transactional
    public FronteiraDTOs.Report importExtract(String companyId, String fileName, byte[] pdf) {
        return importExtract(company(companyId), fileName, pdf, null);
    }

    /**
     * The extract the e-Fisco sync fetched (EfiscoSyncService) - no user here: the company comes from the firm whose
     * accountant's certificate logged in, and the import is signed "e-Fisco (automático)".
     */
    @Transactional
    public FronteiraDTOs.Report importFromEfisco(Company company, byte[] pdf) {
        return importExtract(company, "e-Fisco-extrato.pdf", pdf, EFISCO_IMPORTER);
    }

    private FronteiraDTOs.Report importExtract(Company company, String fileName, byte[] pdf, String importedBy) {
        if (company.getUf() != null && !UF.equalsIgnoreCase(company.getUf().strip())) {
            throw new BadRequestException("error.fronteira.company.not.pe", messageService.getMessage("error.fronteira.company.not.pe"));
        }
        if (pdf == null || pdf.length == 0) {
            throw new BadRequestException("error.fronteira.file.required", messageService.getMessage("error.fronteira.file.required"));
        }
        EfiscoExtractParser.Extract read;
        try {
            read = parser.parse(pdf);
        } catch (EfiscoExtractParser.InvalidExtractException e) {
            throw new BadRequestException("error.fronteira.extract.invalid", messageService.getMessage("error.fronteira.extract.invalid", e.getMessage()));
        }
        checkStateRegistration(company, read.stateRegistration());

        EfiscoExtract extract = extractRepository
                .findByCompany_IdAndPeriodAndDeletedIsFalse(company.getId(), read.period().toString())
                .orElseGet(() -> {
                    EfiscoExtract created = new EfiscoExtract();
                    created.setCompany(company);
                    created.setPeriod(read.period().toString());
                    created.setDeleted(false);
                    return created;
                });
        extract.setExtractNumber(read.extractNumber());
        extract.setStateRegistration(read.stateRegistration());
        extract.setLegalName(read.legalName());
        extract.setAre(read.are());
        extract.setRevenueCode(read.revenueCode());
        extract.setCredenciamentoAntecipacao(read.credenciamentoAntecipacao());
        extract.setCredenciamentoInterno(read.credenciamentoInterno());
        extract.setDueDate(read.dueDate());
        extract.setIssuedAt(read.issuedAt());
        extract.setIcmsARecolher(read.totals().get(EfiscoExtractParser.A_RECOLHER));
        extract.setIcmsPago(read.totals().get(EfiscoExtractParser.PAGO));
        extract.setIcmsEmProcesso(read.totals().get(EfiscoExtractParser.EM_PROCESSO));
        extract.setIcmsAutuado(read.totals().get(EfiscoExtractParser.AUTUADO));
        extract.setIcmsMandadoSeguranca(read.totals().get(EfiscoExtractParser.MANDADO_SEGURANCA));
        extract.setIcmsAnistiado(read.totals().get(EfiscoExtractParser.ANISTIADO));
        extract.setIcmsRegDebito(read.totals().get(EfiscoExtractParser.REG_DEBITO));
        extract.setFileName(fileName == null ? null : fileName.length() > 255 ? fileName.substring(0, 255) : fileName);
        extract.setImportedAt(LocalDateTime.now());
        extract.setImportedBy(importedBy != null ? importedBy : auditorAware.getCurrentAuditor().orElse(SYSTEM));

        List<EfiscoExtractNote> notes = new ArrayList<>();
        for (EfiscoExtractParser.Note note : read.notes()) {
            EfiscoExtractNote row = new EfiscoExtractNote();
            row.setLineNumber(note.line());
            row.setRecordNumber(note.recordNumber());
            row.setCrossingDate(note.crossingDate());
            row.setInvoiceNumber(note.invoiceNumber());
            row.setUf(note.uf());
            row.setInvoiceAmount(note.invoiceAmount());
            row.setIcmsDue(note.icmsDue());
            row.setIssuerCnpj(note.issuerCnpj());
            row.setDeleted(false);
            notes.add(row);
        }
        extract.replaceNotes(notes);
        keepPdf(extract, company, pdf);
        extractRepository.save(extract);
        return report(company, read.period());
    }

    /** An extract of another IE is refused; a company without any IE registered takes it. */
    private void checkStateRegistration(Company company, String inscricaoEstadual) {
        String read = inscricaoEstadual == null ? "" : inscricaoEstadual.replaceAll("\\D", "");
        List<StateRegistration> registrations = company.getStateRegistrations();
        if (read.isEmpty() || registrations.isEmpty()) {
            return;
        }
        boolean matches = registrations.stream()
                .map(registration -> registration.getNumber() == null ? "" : registration.getNumber().replaceAll("\\D", ""))
                .anyMatch(number -> !number.isEmpty() && stripZeros(number).equals(stripZeros(read)));
        if (!matches) {
            throw new BadRequestException("error.fronteira.extract.other.ie", messageService.getMessage("error.fronteira.extract.other.ie", inscricaoEstadual));
        }
    }

    // ---------------------------------------------------------------- relatório (PDF / CSV)

    /** The month's report to keep and to send: the guide after the adjustments, the notes to revise, the rules used. */
    @Transactional
    public byte[] reportPdf(String companyId, String requestedPeriod) {
        Company company = company(companyId);
        FronteiraDTOs.Report report = report(companyId, requestedPeriod);
        return FronteiraReportPdf.pdf(report, documentOf(company), FronteiraReportPdf.rulesInfo(ruleService.rulesFor(company.getId())));
    }

    /** The same notes as a spreadsheet (opens in Excel in Portuguese). */
    @Transactional
    public byte[] reportCsv(String companyId, String requestedPeriod) {
        Company company = company(companyId);
        FronteiraDTOs.Report report = report(companyId, requestedPeriod);
        return FronteiraReportPdf.csv(report, documentOf(company), FronteiraReportPdf.rulesInfo(ruleService.rulesFor(company.getId())));
    }

    /** The draft of the revision request (PDF) of the notes e-Fisco charged beyond Taxify's calculation. */
    @Transactional
    public byte[] revisionRequestPdf(String companyId, String requestedPeriod) {
        return FronteiraRevisionRequestPdf.pdf(revisionDraft(companyId, requestedPeriod));
    }

    /** The same draft as plain text, to paste in e-Fisco's form. */
    @Transactional
    public byte[] revisionRequestText(String companyId, String requestedPeriod) {
        return FronteiraRevisionRequestPdf.textBytes(revisionDraft(companyId, requestedPeriod));
    }

    private FronteiraRevisionRequestPdf.Draft revisionDraft(String companyId, String requestedPeriod) {
        Company company = company(companyId);
        FronteiraDTOs.Report report = report(companyId, requestedPeriod);
        if (report.extract() == null) {
            throw new ConflictException("error.fronteira.revision.no.extract", messageService.getMessage("error.fronteira.revision.no.extract"));
        }
        if (!FronteiraRevisionRequestPdf.hasItems(report)) {
            throw new ConflictException("error.fronteira.revision.none", messageService.getMessage("error.fronteira.revision.none"));
        }
        FronteiraRevisionRequestPdf.Taxpayer taxpayer = new FronteiraRevisionRequestPdf.Taxpayer(company.getName(),
                documentOf(company), company.getStateRegistration(), company.getAddressCity());
        return FronteiraRevisionRequestPdf.draft(report, taxpayer,
                FronteiraReportPdf.rulesInfo(ruleService.rulesFor(company.getId())), LocalDate.now());
    }

    private static String documentOf(Company company) {
        return company.getDocument() == null ? null : company.getDocument().getNumber();
    }

    // ---------------------------------------------------------------- report

    /** Below this (R$) a difference between e-Fisco and Taxify is rounding, not a charge. */
    static final BigDecimal CALC_TOLERANCE = new BigDecimal("0.03");

    @Transactional
    public FronteiraDTOs.Report report(String companyId, String requestedPeriod) {
        return report(companyId, requestedPeriod, null);
    }

    /**
     * The comparison of a period; with {@code cfops} (CF-RN-20, a 400 when broken), also the CFOP cut
     * ({@code cfopFilter}) - the rest of the report stays of the whole extract (CF-RN-24).
     */
    @Transactional
    public FronteiraDTOs.Report report(String companyId, String requestedPeriod, List<String> cfops) {
        long started = System.nanoTime();
        Company company = company(companyId);
        CfopFilter cfopFilter = cfopFilterValidation.check(cfops);
        List<EfiscoExtract> extracts = extractRepository.findByCompany_IdAndDeletedIsFalseOrderByPeriodDesc(company.getId());
        YearMonth month = requestedPeriod == null || requestedPeriod.isBlank()
                ? (extracts.isEmpty() ? YearMonth.now() : YearMonth.parse(extracts.get(0).getPeriod()))
                : month(requestedPeriod);
        FronteiraDTOs.Report report = report(company, month);
        if (cfopFilter.isEmpty()) {
            return report;
        }
        FronteiraDTOs.CfopSelection selection = cfopSelection(cfopFilter, report.lines(), report.semCobranca());
        if (log.isDebugEnabled()) {
            log.debug("fronteira cfop cut: company {}, period {}, {} cfop(s), {} matching line(s), {} without xml, {} ms",
                    company.getId(), month, cfopFilter.values().size(), selection.matchingLines().size(),
                    selection.linesWithoutXml(), (System.nanoTime() - started) / 1_000_000);
        }
        return report.withCfopFilter(selection);
    }

    /**
     * CF-RN-21 to 27: the lines and the entries without a charge whose note has one of the CFOPs (the supplier's, the
     * ones the screen shows), and the {@link Tally} of the matching lines - the same accounts as the summary, nothing
     * recalculated. A line without a note here never matches.
     */
    static FronteiraDTOs.CfopSelection cfopSelection(CfopFilter filter, List<FronteiraDTOs.Line> lines,
                                                     List<FronteiraDTOs.Uncharged> uncharged) {
        Tally cut = new Tally();
        List<Integer> matchingLines = new ArrayList<>();
        int withoutXml = 0;
        for (FronteiraDTOs.Line line : lines) {
            if (line.nota() == null) {
                withoutXml++;
            } else if (filter.matchesAny(line.nota().cfops())) {
                matchingLines.add(line.linha());
                cut.add(line);
            }
        }
        matchingLines.sort(Comparator.naturalOrder());
        List<FronteiraDTOs.Uncharged> matchingEntries = uncharged.stream().filter(entry -> filter.matchesAny(entry.cfops())).toList();
        BigDecimal preview = matchingEntries.stream().map(FronteiraDTOs.Uncharged::calculo).filter(java.util.Objects::nonNull)
                .map(FronteiraRuleDTOs.NoteCalc::valor).reduce(BigDecimal.ZERO, BigDecimal::add);
        FronteiraDTOs.CfopSelectionTotals totals = new FronteiraDTOs.CfopSelectionTotals(matchingLines.size(),
                cut.invoicesAmount, cut.icmsDevido, cut.icmsCalculado, cut.cobradoAMais, cut.cobradoAMenos,
                matchingEntries.size(), preview);
        return new FronteiraDTOs.CfopSelection(filter.values(), List.copyOf(matchingLines),
                matchingEntries.stream().map(FronteiraDTOs.Uncharged::documentId).toList(), withoutXml, totals);
    }

    private FronteiraDTOs.Report report(Company company, YearMonth month) {
        List<FronteiraDTOs.Period> periods = extractRepository.findByCompany_IdAndDeletedIsFalseOrderByPeriodDesc(company.getId())
                .stream()
                .map(extract -> new FronteiraDTOs.Period(extract.getPeriod(), extract.getExtractNumber(),
                        extract.getNotes().stream().map(EfiscoExtractNote::getIcmsDue).reduce(BigDecimal.ZERO, BigDecimal::add),
                        extract.getNotes().size(), extract.getImportedAt()))
                .toList();
        EfiscoExtract extract = extractRepository
                .findByCompany_IdAndPeriodAndDeletedIsFalse(company.getId(), month.toString()).orElse(null);
        List<EfiscoExtractNote> notes = extract == null ? List.of() : extract.getNotes();
        if (extract != null) {
            ruleService.ensureCargaLiquida(company, extract.getCredenciamentoInterno(), predominantRate(notes), extract.getExtractNumber());
        }

        Map<String, GoodsInvoice> documents = documentsOf(company.getId(), notes);
        List<GoodsInvoice> entries = interstateEntries(company.getId(), month, new HashSet<>(documents.keySet()));
        List<GoodsInvoice> all = new ArrayList<>(documents.values());
        all.addAll(entries);

        // the month's credenciamento, or - for the prévia - the latest one the company had
        String credenciamento = extract != null ? extract.getCredenciamentoInterno()
                : extractRepository.findByCompany_IdAndDeletedIsFalseOrderByPeriodDesc(company.getId()).stream()
                        .map(EfiscoExtract::getCredenciamentoInterno).filter(java.util.Objects::nonNull).findFirst().orElse(null);
        FronteiraCalculator.Context context = new FronteiraCalculator.Context(ruleService.rulesFor(company.getId()),
                company.getTaxRegimeType() == TaxRegime.SIMPLES_NACIONAL, credenciamento,
                br.com.cshub.taxify.domain.fiscalrule.ProductRules.of(productRuleRepository.findByCompany_IdAndDeletedIsFalse(company.getId())));
        Map<UUID, List<FiscalDocumentItem>> itemsByDocument = itemsOf(all);
        Map<UUID, FronteiraRuleDTOs.NoteCalc> calcs = new HashMap<>();
        for (GoodsInvoice document : all) {
            if (!document.isCanceled()) {
                calcs.put(document.getId(), toDto(FronteiraCalculator.calculate(document,
                        itemsByDocument.getOrDefault(document.getId(), List.of()), context)));
            }
        }
        Map<UUID, List<String>> cfops = cfopsOf(all.stream().map(GoodsInvoice::getId).toList());

        List<FronteiraDTOs.Uncharged> uncharged = entries.stream()
                .map(document -> new FronteiraDTOs.Uncharged(document.getId(), document.getNumber(), document.getIssuer().name(),
                        document.getIssuer().cnpj(), document.getIssuer().uf(), document.getIssueDate(), document.getTotalAmount(),
                        cfops.getOrDefault(document.getId(), List.of()), calcs.get(document.getId())))
                .toList();
        BigDecimal previa = uncharged.stream().map(FronteiraDTOs.Uncharged::calculo)
                .filter(calc -> calc != null)
                .map(FronteiraRuleDTOs.NoteCalc::valor).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (extract == null) {
            return new FronteiraDTOs.Report(company.getId(), company.getName(), month.toString(), null, null,
                    List.of(), uncharged, previa, periods);
        }

        Map<UUID, DivergenceSummary> divergences = divergenceService.summarize(company.getId(), documents.values());
        Set<String> summaryOnly = summaryOnlyKeys(company.getId());
        BigDecimal predominant = predominantRate(notes);

        List<FronteiraDTOs.Line> lines = new ArrayList<>();
        Tally tally = new Tally();
        for (EfiscoExtractNote note : notes) {
            BigDecimal rate = rate(note);
            List<Flag> flags = new ArrayList<>();
            BigDecimal differenceToRate = null;
            if (predominant != null && rate != null && note.getIcmsDue().signum() > 0 && offRate(note, rate, predominant)) {
                differenceToRate = note.getIcmsDue().subtract(expected(note, predominant));
                flags.add(Flag.FORA_DA_TAXA);
            }
            String key = key(note.getIssuerCnpj(), note.getInvoiceNumber());
            GoodsInvoice document = documents.get(key);
            FronteiraDTOs.NoteRef ref = null;
            FronteiraRuleDTOs.NoteCalc calc = null;
            if (document == null) {
                flags.add(summaryOnly.contains(key) ? Flag.SO_RESUMO : Flag.SEM_XML);
            } else {
                if (document.isCanceled()) {
                    flags.add(Flag.NOTA_CANCELADA);
                }
                if (document.getTotalAmount() != null
                        && document.getTotalAmount().subtract(note.getInvoiceAmount()).abs().compareTo(new BigDecimal("0.01")) > 0) {
                    flags.add(Flag.VALOR_DIFERENTE);
                }
                DivergenceSummary divergence = divergences.get(document.getId());
                if (divergence != null) {
                    flags.add(Flag.DIVERGENCIA_FISCAL);
                }
                ref = new FronteiraDTOs.NoteRef(document.getId(), document.getIssuer().name(), document.getIssueDate(),
                        document.getTotalAmount(), document.getOperationNature(),
                        cfops.getOrDefault(document.getId(), List.of()), document.isCanceled(), divergence,
                        document.getAccessKey());
                calc = calcs.get(document.getId());
            }
            Result resultado = resultado(note.getIcmsDue(), document, calc);
            BigDecimal difference = calc == null || resultado == Result.SEM_REGRA || resultado == Result.PENDENTE
                    ? null : note.getIcmsDue().subtract(calc.valor());
            boolean reviewed = calc == null || calc.conferido();
            FronteiraDTOs.Line line = new FronteiraDTOs.Line(note.getLineNumber(), note.getRecordNumber(), note.getCrossingDate(),
                    note.getInvoiceNumber(), note.getUf(), note.getIssuerCnpj(), note.getInvoiceAmount(), note.getIcmsDue(), rate,
                    differenceToRate, List.copyOf(flags), ref, resultado, reviewed, difference, calc);
            tally.add(line);
            lines.add(line);
        }

        FronteiraDTOs.Summary summary = new FronteiraDTOs.Summary(notes.size(), tally.invoicesAmount, tally.icmsDevido, predominant,
                tally.outsideRate, tally.icmsOutsideRate, tally.differenceOutsideRate, tally.semXml, tally.differentAmount,
                tally.canceled, tally.withDivergence, uncharged.size(), tally.icmsCalculado, tally.cobradoAMais,
                tally.cobradoAMenos, tally.confere, tally.aMais, tally.aMenos, tally.notCalculated, tally.withoutRule,
                tally.pendentes, tally.toReview, tally.icmsDevido.subtract(tally.cobradoAMais));
        return new FronteiraDTOs.Report(company.getId(), company.getName(), month.toString(), toDto(extract), summary,
                lines, uncharged, previa, periods);
    }

    /** The charge against the calculation's range: rounding differences up to {@link #CALC_TOLERANCE} confer. */
    static Result resultado(BigDecimal charged, GoodsInvoice document, FronteiraRuleDTOs.NoteCalc calc) {
        if (document == null) {
            return Result.PENDENTE;
        }
        if (document.isCanceled()) {
            return charged.signum() > 0 ? Result.COBROU_A_MAIS : Result.CONFERE;
        }
        if (calc == null || calc.itens().isEmpty()) {
            return Result.PENDENTE;
        }
        if (calc.semRegra()) {
            return Result.SEM_REGRA;
        }
        if (charged.signum() == 0 && calc.valor().compareTo(CALC_TOLERANCE) > 0) {
            return Result.NAO_CALCULOU;
        }
        if (charged.compareTo(calc.maximo().add(CALC_TOLERANCE)) > 0) {
            return Result.COBROU_A_MAIS;
        }
        if (charged.compareTo(calc.minimo().subtract(CALC_TOLERANCE)) < 0) {
            return Result.COBROU_A_MENOS;
        }
        return Result.CONFERE;
    }

    private static final class Tally {
        BigDecimal invoicesAmount = BigDecimal.ZERO;
        BigDecimal icmsDevido = BigDecimal.ZERO;
        BigDecimal icmsOutsideRate = BigDecimal.ZERO;
        BigDecimal differenceOutsideRate = BigDecimal.ZERO;
        BigDecimal icmsCalculado = BigDecimal.ZERO;
        BigDecimal cobradoAMais = BigDecimal.ZERO;
        BigDecimal cobradoAMenos = BigDecimal.ZERO;
        int outsideRate, semXml, differentAmount, canceled, withDivergence;
        int confere, aMais, aMenos, notCalculated, withoutRule, pendentes, toReview;

        /** One line of the extract, from what the line itself carries - the summary and the CFOP cut alike (CF-RN-25). */
        void add(FronteiraDTOs.Line line) {
            invoicesAmount = invoicesAmount.add(line.valorNota());
            icmsDevido = icmsDevido.add(line.icmsDevido());
            if (line.flags().contains(Flag.FORA_DA_TAXA)) {
                outsideRate++;
                icmsOutsideRate = icmsOutsideRate.add(line.icmsDevido());
                differenceOutsideRate = differenceOutsideRate.add(line.diferencaParaTaxa());
            }
            if (line.flags().contains(Flag.SEM_XML) || line.flags().contains(Flag.SO_RESUMO)) {
                semXml++;
            }
            if (line.flags().contains(Flag.NOTA_CANCELADA)) {
                canceled++;
            }
            if (line.flags().contains(Flag.VALOR_DIFERENTE)) {
                differentAmount++;
            }
            if (line.flags().contains(Flag.DIVERGENCIA_FISCAL)) {
                withDivergence++;
            }
            count(line.resultado(), line.diferenca(), line.calculo(), line.conferido());
        }

        private void count(Result resultado, BigDecimal difference, FronteiraRuleDTOs.NoteCalc calc, boolean reviewed) {
            if (calc != null && !calc.semRegra()) {
                icmsCalculado = icmsCalculado.add(calc.valor());
            }
            if (!reviewed && resultado != Result.SEM_REGRA && resultado != Result.PENDENTE) {
                toReview++;
            }
            switch (resultado) {
                case CONFERE -> confere++;
                case COBROU_A_MAIS -> {
                    aMais++;
                    if (difference != null) {
                        cobradoAMais = cobradoAMais.add(difference);
                    }
                }
                case COBROU_A_MENOS -> {
                    aMenos++;
                    cobradoAMenos = cobradoAMenos.add(difference.negate());
                }
                case NAO_CALCULOU -> {
                    notCalculated++;
                    cobradoAMenos = cobradoAMenos.add(difference.negate());
                }
                case SEM_REGRA -> withoutRule++;
                case PENDENTE -> pendentes++;
            }
        }
    }

    /** issuer CNPJ + number → the company's note (one query for the whole extract). */
    private Map<String, GoodsInvoice> documentsOf(UUID companyId, Collection<EfiscoExtractNote> notes) {
        Set<String> cnpjs = new HashSet<>();
        Set<String> numbers = new HashSet<>();
        for (EfiscoExtractNote note : notes) {
            cnpjs.add(note.getIssuerCnpj());
            numbers.add(note.getInvoiceNumber());
        }
        Map<String, GoodsInvoice> byKey = new HashMap<>();
        if (cnpjs.isEmpty()) {
            return byKey;
        }
        for (GoodsInvoice document : documentRepository.findByIssuerCnpjsAndNumbers(companyId, cnpjs, numbers)) {
            // the company's own copy of the entry wins over anything else with the same key
            byKey.merge(key(document.getIssuer().cnpj(), document.getNumber()), document,
                    (current, other) -> current.getDirection() == FiscalDirection.INBOUND ? current : other);
        }
        return byKey;
    }

    private Map<UUID, List<FiscalDocumentItem>> itemsOf(List<GoodsInvoice> documents) {
        Map<UUID, List<FiscalDocumentItem>> items = new HashMap<>();
        List<UUID> ids = documents.stream().map(GoodsInvoice::getId).distinct().toList();
        for (int start = 0; start < ids.size(); start += 500) {
            for (FiscalDocumentItem item : itemRepository.findByDocument_IdIn(ids.subList(start, Math.min(ids.size(), start + 500)))) {
                items.computeIfAbsent(item.getDocument().getId(), id -> new ArrayList<>()).add(item);
            }
        }
        return items;
    }

    private Map<UUID, List<String>> cfopsOf(List<UUID> documentIds) {
        Map<UUID, Set<String>> cfops = new HashMap<>();
        if (!documentIds.isEmpty()) {
            for (Object[] row : itemRepository.findDistinctCfopsByDocumentIds(documentIds)) {
                String cfop = ((String) row[2]).strip();
                if (!cfop.isEmpty()) {
                    cfops.computeIfAbsent((UUID) row[0], id -> new TreeSet<>()).add(cfop);
                }
            }
        }
        Map<UUID, List<String>> result = new HashMap<>();
        cfops.forEach((id, set) -> result.put(id, List.copyOf(set)));
        return result;
    }

    /** Entries from another UF issued in the month, not canceled, that the extract doesn't list. */
    private List<GoodsInvoice> interstateEntries(UUID companyId, YearMonth month, Set<String> charged) {
        return documentRepository.findIssuedBetween(
                        companyId, month.atDay(1).atStartOfDay(), month.atEndOfMonth().atTime(23, 59, 59, 999_999_999))
                .stream()
                .filter(document -> !document.isDeleted() && !document.isCanceled())
                .filter(document -> document.getDirection() == FiscalDirection.INBOUND)
                .filter(document -> document.getIssuer().uf() != null && !UF.equalsIgnoreCase(document.getIssuer().uf().strip()))
                .filter(document -> !charged.contains(key(document.getIssuer().cnpj(), document.getNumber())))
                .sorted(Comparator.comparing(GoodsInvoice::getIssueDate, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /** issuer + number of the entries that SEFAZ handed over only as a summary (from the access key). */
    private Set<String> summaryOnlyKeys(UUID companyId) {
        Set<String> keys = new HashSet<>();
        for (SefazNoteSummary summary : summaryRepository.findByCompany_IdAndCompletedAtIsNullOrderByIssueDateAsc(companyId, Pageable.unpaged())) {
            String accessKey = summary.getAccessKey();
            if (accessKey != null && accessKey.length() == 44) {
                keys.add(key(accessKey.substring(6, 20), accessKey.substring(25, 34)));
            }
        }
        return keys;
    }

    private static FronteiraRuleDTOs.NoteCalc toDto(FronteiraCalculator.NoteResult result) {
        return new FronteiraRuleDTOs.NoteCalc(result.value(), result.min(), result.max(), result.reviewed(), result.semRegra(),
                result.items().stream().map(item -> new FronteiraRuleDTOs.ItemCalc(item.numeroItem(), item.codigo(), item.descricao(),
                        item.ncm(), item.cfop(), item.treatment().name(), item.treatment().label, item.partida(), item.value(),
                        item.min(), item.max(), item.reviewed(), item.rulesUsed(), item.memoria(), item.semRegraMotivo(),
                        item.variables())).toList());
    }

    // ---------------------------------------------------------------- rates

    /** ICMS devido / amount da NF in %, 4 places; null on a zero-value note. */
    static BigDecimal rate(EfiscoExtractNote note) {
        if (note.getInvoiceAmount() == null || note.getInvoiceAmount().signum() == 0) {
            return null;
        }
        return note.getIcmsDue().multiply(HUNDRED).divide(note.getInvoiceAmount(), 4, RoundingMode.HALF_UP);
    }

    /**
     * The rate most notes were charged at, to one decimal place (5,50% shows as 5.5) - ties go to the
     * lowest. The notes' rates round off on small values, hence one decimal, not two.
     */
    static BigDecimal predominantRate(List<EfiscoExtractNote> notes) {
        Map<BigDecimal, Integer> counts = new TreeMap<>();
        for (EfiscoExtractNote note : notes) {
            BigDecimal rate = rate(note);
            if (rate != null) {
                counts.merge(rate.setScale(1, RoundingMode.HALF_UP), 1, Integer::sum);
            }
        }
        if (counts.values().stream().mapToInt(Integer::intValue).sum() < MIN_NOTES_FOR_RATE) {
            return null;
        }
        return counts.entrySet().stream()
                .max(Comparator.comparingInt((Map.Entry<BigDecimal, Integer> entry) -> entry.getValue())
                        .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private static boolean offRate(EfiscoExtractNote note, BigDecimal rate, BigDecimal predominant) {
        return rate.subtract(predominant).abs().compareTo(RATE_TOLERANCE) > 0
                && note.getIcmsDue().subtract(expected(note, predominant)).abs().compareTo(MONEY_TOLERANCE) > 0;
    }

    private static BigDecimal expected(EfiscoExtractNote note, BigDecimal rate) {
        return note.getInvoiceAmount().multiply(rate).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    // ---------------------------------------------------------------- helpers

    private static String key(String cnpj, String numero) {
        return (cnpj == null ? "" : cnpj.replaceAll("\\D", "")) + "|" + (numero == null ? "" : stripZeros(numero.replaceAll("\\D", "")));
    }

    private static String stripZeros(String digits) {
        return digits.replaceFirst("^0+(?=\\d)", "");
    }

    /**
     * The extract e-Fisco gave is the one already imported - same number, notes, ICMS devido, a recolher and pago:
     * the e-Fisco sync keeps what it has instead of importing it again.
     */
    @Transactional(readOnly = true)
    public boolean sameAsImported(Company company, byte[] pdf) {
        EfiscoExtractParser.Extract read;
        try {
            read = parser.parse(pdf);
        } catch (EfiscoExtractParser.InvalidExtractException e) {
            return false; // the import says why
        }
        BigDecimal readDue = read.notes().stream().map(EfiscoExtractParser.Note::icmsDue).filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return extractRepository.findByCompany_IdAndPeriodAndDeletedIsFalse(company.getId(), read.period().toString())
                .filter(existing -> java.util.Objects.equals(existing.getExtractNumber(), read.extractNumber())
                        && existing.getNotes().size() == read.notes().size()
                        && sameValue(existing.getIcmsARecolher(), read.total(EfiscoExtractParser.A_RECOLHER))
                        && sameValue(existing.getIcmsPago(), read.total(EfiscoExtractParser.PAGO))
                        && sameValue(existing.getNotes().stream().map(EfiscoExtractNote::getIcmsDue).filter(java.util.Objects::nonNull)
                        .reduce(BigDecimal.ZERO, BigDecimal::add), readDue))
                .isPresent();
    }

    /**
     * The month's extract is closed - past its due date, or paid in full: it doesn't change any more, so the scheduled
     * e-Fisco sync stops asking for it.
     */
    @Transactional(readOnly = true)
    public boolean extractClosed(Company company, YearMonth period) {
        return extractRepository.findByCompany_IdAndPeriodAndDeletedIsFalse(company.getId(), period.toString())
                .filter(extract -> (extract.getDueDate() != null && extract.getDueDate().isBefore(java.time.LocalDate.now()))
                        || (extract.getIcmsPago() != null && extract.getIcmsPago().signum() > 0
                        && (extract.getIcmsARecolher() == null || extract.getIcmsARecolher().signum() == 0)))
                .isPresent();
    }

    private static boolean sameValue(BigDecimal a, BigDecimal b) {
        return (a == null ? BigDecimal.ZERO : a).compareTo(b == null ? BigDecimal.ZERO : b) == 0;
    }

    /** The kept PDF of a company's extract of a period - for "Ver PDF"; the user's access to the company checked. */
    @Transactional(readOnly = true)
    public byte[] extractPdf(String companyId, String requestedPeriod) {
        Company company = company(companyId);
        EfiscoExtract extract = extractRepository.findByCompany_IdAndPeriodAndDeletedIsFalse(company.getId(), month(requestedPeriod).toString())
                .filter(found -> found.getPdfPath() != null)
                .orElseThrow(() -> new br.com.cshub.taxify.exception.ResourceNotFoundException(messageService.getMessage("error.entity.not.found")));
        try {
            return storage.read(extract.getPdfPath());
        } catch (java.io.IOException e) {
            throw new br.com.cshub.taxify.exception.ResourceNotFoundException(messageService.getMessage("error.entity.not.found"));
        }
    }

    /**
     * The e-Fisco sync found the same extract already imported, but imported before PDFs were kept: keeps this one,
     * so "Ver PDF" works without importing it again.
     */
    @Transactional
    public void attachPdfIfMissing(Company company, byte[] pdf) {
        EfiscoExtractParser.Extract read;
        try {
            read = parser.parse(pdf);
        } catch (EfiscoExtractParser.InvalidExtractException e) {
            return;
        }
        extractRepository.findByCompany_IdAndPeriodAndDeletedIsFalse(company.getId(), read.period().toString())
                .filter(extract -> extract.getPdfPath() == null)
                .ifPresent(extract -> {
                    keepPdf(extract, company, pdf);
                    extractRepository.save(extract);
                });
    }

    /** Stores the PDF beside the extract (replacing the previous one); a storage failure doesn't fail the import. */
    private void keepPdf(EfiscoExtract extract, Company company, byte[] pdf) {
        String previous = extract.getPdfPath();
        try {
            extract.setPdfPath(storage.storeExtractPdf(company.getId(), extract.getPeriod(), pdf));
        } catch (java.io.IOException | RuntimeException e) {
            log.warn("Could not keep the extract PDF of company {} ({})", company.getId(), extract.getPeriod(), e);
            return;
        }
        if (previous != null) {
            try {
                storage.delete(previous);
            } catch (java.io.IOException | RuntimeException e) {
                log.warn("Could not delete the previous extract PDF {}", previous, e);
            }
        }
    }

    /** The company the screen asks to sync with e-Fisco - the user's access checked, and it must be from PE. */
    public Company companyOfPernambuco(String companyId) {
        Company company = company(companyId);
        if (company.getUf() != null && !UF.equalsIgnoreCase(company.getUf().strip())) {
            throw new BadRequestException("error.fronteira.company.not.pe", messageService.getMessage("error.fronteira.company.not.pe"));
        }
        return company;
    }

    /** "2026-11", or the current month. */
    public YearMonth periodOrCurrent(String requestedPeriod) {
        return requestedPeriod == null || requestedPeriod.isBlank() ? YearMonth.now() : month(requestedPeriod);
    }

    private Company company(String companyId) {
        try {
            return companyValidation.checkExist(UUID.fromString(companyId.strip()));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BadRequestException("error.generic.bad.request", messageService.getMessage("error.generic.bad.request"));
        }
    }

    private YearMonth month(String requestedPeriod) {
        try {
            return YearMonth.parse(requestedPeriod.strip());
        } catch (DateTimeParseException e) {
            throw new BadRequestException("error.generic.bad.request", messageService.getMessage("error.generic.bad.request"));
        }
    }

    private static FronteiraDTOs.Extract toDto(EfiscoExtract extract) {
        return new FronteiraDTOs.Extract(extract.getPeriod(), extract.getExtractNumber(), extract.getStateRegistration(),
                extract.getLegalName(), extract.getAre(), extract.getRevenueCode(), extract.getCredenciamentoAntecipacao(),
                extract.getCredenciamentoInterno(), extract.getDueDate(), extract.getIssuedAt(),
                extract.getIcmsARecolher(), extract.getIcmsPago(), extract.getIcmsEmProcesso(), extract.getIcmsAutuado(),
                extract.getIcmsMandadoSeguranca(), extract.getIcmsAnistiado(), extract.getIcmsRegDebito(),
                extract.getFileName(), extract.getImportedAt(), extract.getImportedBy(), extract.getPdfPath() != null);
    }
}
