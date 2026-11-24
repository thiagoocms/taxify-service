package br.com.cshub.taxify.service.fronteira;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.company.StateRegistration;
import br.com.cshub.taxify.domain.fronteira.EfiscoExtract;
import br.com.cshub.taxify.domain.fronteira.EfiscoExtractNote;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlSummaryDTO.DivergenceSummary;
import br.com.cshub.taxify.dto.fronteira.FronteiraDTOs;
import br.com.cshub.taxify.dto.fronteira.FronteiraDTOs.Flag;
import br.com.cshub.taxify.dto.fronteira.FronteiraDTOs.Resultado;
import br.com.cshub.taxify.dto.fronteira.FronteiraRuleDTOs;
import br.com.cshub.taxify.domain.xml.SefazNoteSummary;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocumentItem;
import br.com.cshub.taxify.enumerated.TaxRegime;
import br.com.cshub.taxify.repository.SefazNoteSummaryRepository;
import org.springframework.data.domain.Pageable;
import br.com.cshub.taxify.enumerated.FiscalDirection;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.repository.EfiscoExtractRepository;
import br.com.cshub.taxify.repository.FiscalXmlDocumentItemRepository;
import br.com.cshub.taxify.repository.FiscalXmlDocumentRepository;
import br.com.cshub.taxify.service.fiscalcheck.DocumentDivergenceService;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
    private final FiscalXmlDocumentRepository documentRepository;
    private final FiscalXmlDocumentItemRepository itemRepository;
    private final DocumentDivergenceService divergenceService;
    private final FronteiraRuleService ruleService;
    private final SefazNoteSummaryRepository summaryRepository;
    private final br.com.cshub.taxify.repository.ProductFiscalRuleRepository productRuleRepository;
    private final CompanyValidation companyValidation;
    private final MessageService messageService;
    private final AuditorAware<String> auditorAware;

    public FronteiraService(EfiscoExtractParser parser, EfiscoExtractRepository extractRepository,
                            FiscalXmlDocumentRepository documentRepository, FiscalXmlDocumentItemRepository itemRepository,
                            DocumentDivergenceService divergenceService, FronteiraRuleService ruleService,
                            SefazNoteSummaryRepository summaryRepository,
                            br.com.cshub.taxify.repository.ProductFiscalRuleRepository productRuleRepository,
                            CompanyValidation companyValidation, MessageService messageService, AuditorAware<String> auditorAware) {
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
            throw new BadRequestException(messageService.getMessage("error.fronteira.company.not.pe"));
        }
        if (pdf == null || pdf.length == 0) {
            throw new BadRequestException(messageService.getMessage("error.fronteira.file.required"));
        }
        EfiscoExtractParser.Extract read;
        try {
            read = parser.parse(pdf);
        } catch (EfiscoExtractParser.InvalidExtractException e) {
            throw new BadRequestException(messageService.getMessage("error.fronteira.extract.invalid", e.getMessage()));
        }
        checkStateRegistration(company, read.inscricaoEstadual());

        EfiscoExtract extract = extractRepository
                .findByCompany_IdAndPeriodoAndDeletedIsFalse(company.getId(), read.periodo().toString())
                .orElseGet(() -> {
                    EfiscoExtract created = new EfiscoExtract();
                    created.setCompany(company);
                    created.setPeriodo(read.periodo().toString());
                    created.setDeleted(false);
                    return created;
                });
        extract.setNumeroExtrato(read.numeroExtrato());
        extract.setInscricaoEstadual(read.inscricaoEstadual());
        extract.setRazaoSocial(read.razaoSocial());
        extract.setAre(read.are());
        extract.setCodigoReceita(read.codigoReceita());
        extract.setCredenciamentoAntecipacao(read.credenciamentoAntecipacao());
        extract.setCredenciamentoInterno(read.credenciamentoInterno());
        extract.setDataVencimento(read.dataVencimento());
        extract.setEmitidoEm(read.emitidoEm());
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
            row.setLinha(note.line());
            row.setRegistro(note.registro());
            row.setDataPassagem(note.dataPassagem());
            row.setNumeroNota(note.numero());
            row.setUf(note.uf());
            row.setValorNota(note.valorNota());
            row.setIcmsDevido(note.icmsDevido());
            row.setCnpjEmitente(note.cnpjEmitente());
            row.setDeleted(false);
            notes.add(row);
        }
        extract.replaceNotes(notes);
        extractRepository.save(extract);
        return report(company, read.periodo());
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
            throw new BadRequestException(messageService.getMessage("error.fronteira.extract.other.ie", inscricaoEstadual));
        }
    }

    // ---------------------------------------------------------------- report

    /** Below this (R$) a difference between e-Fisco and Taxify is rounding, not a charge. */
    static final BigDecimal CALC_TOLERANCE = new BigDecimal("0.03");

    @Transactional
    public FronteiraDTOs.Report report(String companyId, String periodo) {
        Company company = company(companyId);
        List<EfiscoExtract> extracts = extractRepository.findByCompany_IdAndDeletedIsFalseOrderByPeriodoDesc(company.getId());
        YearMonth month = periodo == null || periodo.isBlank()
                ? (extracts.isEmpty() ? YearMonth.now() : YearMonth.parse(extracts.get(0).getPeriodo()))
                : month(periodo);
        return report(company, month);
    }

    private FronteiraDTOs.Report report(Company company, YearMonth month) {
        List<FronteiraDTOs.Period> periods = extractRepository.findByCompany_IdAndDeletedIsFalseOrderByPeriodoDesc(company.getId())
                .stream()
                .map(extract -> new FronteiraDTOs.Period(extract.getPeriodo(), extract.getNumeroExtrato(),
                        extract.getNotes().stream().map(EfiscoExtractNote::getIcmsDevido).reduce(BigDecimal.ZERO, BigDecimal::add),
                        extract.getNotes().size(), extract.getImportedAt()))
                .toList();
        EfiscoExtract extract = extractRepository
                .findByCompany_IdAndPeriodoAndDeletedIsFalse(company.getId(), month.toString()).orElse(null);
        List<EfiscoExtractNote> notes = extract == null ? List.of() : extract.getNotes();
        if (extract != null) {
            ruleService.ensureCargaLiquida(company, extract.getCredenciamentoInterno(), predominantRate(notes), extract.getNumeroExtrato());
        }

        Map<String, FiscalXmlDocument> documents = documentsOf(company.getId(), notes);
        List<FiscalXmlDocument> entries = interstateEntries(company.getId(), month, new HashSet<>(documents.keySet()));
        List<FiscalXmlDocument> all = new ArrayList<>(documents.values());
        all.addAll(entries);

        // the month's credenciamento, or - for the prévia - the latest one the company had
        String credenciamento = extract != null ? extract.getCredenciamentoInterno()
                : extractRepository.findByCompany_IdAndDeletedIsFalseOrderByPeriodoDesc(company.getId()).stream()
                        .map(EfiscoExtract::getCredenciamentoInterno).filter(java.util.Objects::nonNull).findFirst().orElse(null);
        FronteiraCalculator.Context context = new FronteiraCalculator.Context(ruleService.rulesFor(company.getId()),
                company.getTaxRegimeType() == TaxRegime.SIMPLES_NACIONAL, credenciamento,
                br.com.cshub.taxify.domain.fiscalrule.ProductRules.of(productRuleRepository.findByCompany_IdAndDeletedIsFalse(company.getId())));
        Map<UUID, List<FiscalXmlDocumentItem>> itemsByDocument = itemsOf(all);
        Map<UUID, FronteiraRuleDTOs.NoteCalc> calcs = new HashMap<>();
        for (FiscalXmlDocument document : all) {
            if (!document.isCanceled()) {
                calcs.put(document.getId(), toDto(FronteiraCalculator.calculate(document,
                        itemsByDocument.getOrDefault(document.getId(), List.of()), context)));
            }
        }
        Map<UUID, List<String>> cfops = cfopsOf(all.stream().map(FiscalXmlDocument::getId).toList());

        List<FronteiraDTOs.Uncharged> uncharged = entries.stream()
                .map(document -> new FronteiraDTOs.Uncharged(document.getId(), document.getNumero(), document.getEmitenteNome(),
                        document.getIssuerCnpj(), document.getEmitenteUf(), document.getIssueDate(), document.getValorTotalNota(),
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
            tally.valorNotas = tally.valorNotas.add(note.getValorNota());
            tally.icmsDevido = tally.icmsDevido.add(note.getIcmsDevido());
            BigDecimal rate = rate(note);
            List<Flag> flags = new ArrayList<>();
            BigDecimal differenceToRate = null;
            if (predominant != null && rate != null && note.getIcmsDevido().signum() > 0 && offRate(note, rate, predominant)) {
                differenceToRate = note.getIcmsDevido().subtract(expected(note, predominant));
                flags.add(Flag.FORA_DA_TAXA);
                tally.foraDaTaxa++;
                tally.icmsForaDaTaxa = tally.icmsForaDaTaxa.add(note.getIcmsDevido());
                tally.diferencaForaDaTaxa = tally.diferencaForaDaTaxa.add(differenceToRate);
            }
            String key = key(note.getCnpjEmitente(), note.getNumeroNota());
            FiscalXmlDocument document = documents.get(key);
            FronteiraDTOs.NoteRef ref = null;
            FronteiraRuleDTOs.NoteCalc calc = null;
            if (document == null) {
                flags.add(summaryOnly.contains(key) ? Flag.SO_RESUMO : Flag.SEM_XML);
                tally.semXml++;
            } else {
                if (document.isCanceled()) {
                    flags.add(Flag.NOTA_CANCELADA);
                    tally.canceladas++;
                }
                if (document.getValorTotalNota() != null
                        && document.getValorTotalNota().subtract(note.getValorNota()).abs().compareTo(new BigDecimal("0.01")) > 0) {
                    flags.add(Flag.VALOR_DIFERENTE);
                    tally.valorDiferente++;
                }
                DivergenceSummary divergence = divergences.get(document.getId());
                if (divergence != null) {
                    flags.add(Flag.DIVERGENCIA_FISCAL);
                    tally.comDivergencia++;
                }
                ref = new FronteiraDTOs.NoteRef(document.getId(), document.getEmitenteNome(), document.getIssueDate(),
                        document.getValorTotalNota(), document.getNaturezaOperacao(),
                        cfops.getOrDefault(document.getId(), List.of()), document.isCanceled(), divergence);
                calc = calcs.get(document.getId());
            }
            Resultado resultado = resultado(note.getIcmsDevido(), document, calc);
            BigDecimal difference = calc == null || resultado == Resultado.SEM_REGRA || resultado == Resultado.PENDENTE
                    ? null : note.getIcmsDevido().subtract(calc.valor());
            boolean reviewed = calc == null || calc.conferido();
            tally.count(resultado, difference, calc, reviewed);
            lines.add(new FronteiraDTOs.Line(note.getLinha(), note.getRegistro(), note.getDataPassagem(), note.getNumeroNota(),
                    note.getUf(), note.getCnpjEmitente(), note.getValorNota(), note.getIcmsDevido(), rate, differenceToRate,
                    List.copyOf(flags), ref, resultado, reviewed, difference, calc));
        }

        FronteiraDTOs.Summary summary = new FronteiraDTOs.Summary(notes.size(), tally.valorNotas, tally.icmsDevido, predominant,
                tally.foraDaTaxa, tally.icmsForaDaTaxa, tally.diferencaForaDaTaxa, tally.semXml, tally.valorDiferente,
                tally.canceladas, tally.comDivergencia, uncharged.size(), tally.icmsCalculado, tally.cobradoAMais,
                tally.cobradoAMenos, tally.confere, tally.aMais, tally.aMenos, tally.naoCalculou, tally.semRegra,
                tally.pendentes, tally.aConferir);
        return new FronteiraDTOs.Report(company.getId(), company.getName(), month.toString(), toDto(extract), summary,
                lines, uncharged, previa, periods);
    }

    /** The charge against the calculation's range: rounding differences up to {@link #CALC_TOLERANCE} confer. */
    static Resultado resultado(BigDecimal charged, FiscalXmlDocument document, FronteiraRuleDTOs.NoteCalc calc) {
        if (document == null) {
            return Resultado.PENDENTE;
        }
        if (document.isCanceled()) {
            return charged.signum() > 0 ? Resultado.COBROU_A_MAIS : Resultado.CONFERE;
        }
        if (calc == null || calc.itens().isEmpty()) {
            return Resultado.PENDENTE;
        }
        if (calc.semRegra()) {
            return Resultado.SEM_REGRA;
        }
        if (charged.signum() == 0 && calc.valor().compareTo(CALC_TOLERANCE) > 0) {
            return Resultado.NAO_CALCULOU;
        }
        if (charged.compareTo(calc.maximo().add(CALC_TOLERANCE)) > 0) {
            return Resultado.COBROU_A_MAIS;
        }
        if (charged.compareTo(calc.minimo().subtract(CALC_TOLERANCE)) < 0) {
            return Resultado.COBROU_A_MENOS;
        }
        return Resultado.CONFERE;
    }

    private static final class Tally {
        BigDecimal valorNotas = BigDecimal.ZERO;
        BigDecimal icmsDevido = BigDecimal.ZERO;
        BigDecimal icmsForaDaTaxa = BigDecimal.ZERO;
        BigDecimal diferencaForaDaTaxa = BigDecimal.ZERO;
        BigDecimal icmsCalculado = BigDecimal.ZERO;
        BigDecimal cobradoAMais = BigDecimal.ZERO;
        BigDecimal cobradoAMenos = BigDecimal.ZERO;
        int foraDaTaxa, semXml, valorDiferente, canceladas, comDivergencia;
        int confere, aMais, aMenos, naoCalculou, semRegra, pendentes, aConferir;

        void count(Resultado resultado, BigDecimal difference, FronteiraRuleDTOs.NoteCalc calc, boolean reviewed) {
            if (calc != null && !calc.semRegra()) {
                icmsCalculado = icmsCalculado.add(calc.valor());
            }
            if (!reviewed && resultado != Resultado.SEM_REGRA && resultado != Resultado.PENDENTE) {
                aConferir++;
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
                    naoCalculou++;
                    cobradoAMenos = cobradoAMenos.add(difference.negate());
                }
                case SEM_REGRA -> semRegra++;
                case PENDENTE -> pendentes++;
            }
        }
    }

    /** issuer CNPJ + number → the company's note (one query for the whole extract). */
    private Map<String, FiscalXmlDocument> documentsOf(UUID companyId, Collection<EfiscoExtractNote> notes) {
        Set<String> cnpjs = new HashSet<>();
        Set<String> numeros = new HashSet<>();
        for (EfiscoExtractNote note : notes) {
            cnpjs.add(note.getCnpjEmitente());
            numeros.add(note.getNumeroNota());
        }
        Map<String, FiscalXmlDocument> byKey = new HashMap<>();
        if (cnpjs.isEmpty()) {
            return byKey;
        }
        for (FiscalXmlDocument document : documentRepository.findByCompany_IdAndIssuerCnpjInAndNumeroInAndDeletedIsFalse(companyId, cnpjs, numeros)) {
            // the company's own copy of the entry wins over anything else with the same key
            byKey.merge(key(document.getIssuerCnpj(), document.getNumero()), document,
                    (current, other) -> current.getDirection() == FiscalDirection.INBOUND ? current : other);
        }
        return byKey;
    }

    private Map<UUID, List<FiscalXmlDocumentItem>> itemsOf(List<FiscalXmlDocument> documents) {
        Map<UUID, List<FiscalXmlDocumentItem>> items = new HashMap<>();
        List<UUID> ids = documents.stream().map(FiscalXmlDocument::getId).distinct().toList();
        for (int start = 0; start < ids.size(); start += 500) {
            for (FiscalXmlDocumentItem item : itemRepository.findByDocument_IdIn(ids.subList(start, Math.min(ids.size(), start + 500)))) {
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
    private List<FiscalXmlDocument> interstateEntries(UUID companyId, YearMonth month, Set<String> charged) {
        return documentRepository.findByCompany_IdAndIssueDateBetweenOrderByIssueDateDesc(
                        companyId, month.atDay(1).atStartOfDay(), month.atEndOfMonth().atTime(23, 59, 59, 999_999_999))
                .stream()
                .filter(document -> !document.isDeleted() && !document.isCanceled())
                .filter(document -> document.getDirection() == FiscalDirection.INBOUND)
                .filter(document -> document.getEmitenteUf() != null && !UF.equalsIgnoreCase(document.getEmitenteUf().strip()))
                .filter(document -> !charged.contains(key(document.getIssuerCnpj(), document.getNumero())))
                .sorted(Comparator.comparing(FiscalXmlDocument::getIssueDate, Comparator.nullsLast(Comparator.naturalOrder())))
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

    /** ICMS devido / valor da NF in %, 4 places; null on a zero-value note. */
    static BigDecimal rate(EfiscoExtractNote note) {
        if (note.getValorNota() == null || note.getValorNota().signum() == 0) {
            return null;
        }
        return note.getIcmsDevido().multiply(HUNDRED).divide(note.getValorNota(), 4, RoundingMode.HALF_UP);
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
                && note.getIcmsDevido().subtract(expected(note, predominant)).abs().compareTo(MONEY_TOLERANCE) > 0;
    }

    private static BigDecimal expected(EfiscoExtractNote note, BigDecimal rate) {
        return note.getValorNota().multiply(rate).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    // ---------------------------------------------------------------- helpers

    private static String key(String cnpj, String numero) {
        return (cnpj == null ? "" : cnpj.replaceAll("\\D", "")) + "|" + (numero == null ? "" : stripZeros(numero.replaceAll("\\D", "")));
    }

    private static String stripZeros(String digits) {
        return digits.replaceFirst("^0+(?=\\d)", "");
    }

    /** The company the screen asks to sync with e-Fisco - the user's access checked, and it must be from PE. */
    public Company companyOfPernambuco(String companyId) {
        Company company = company(companyId);
        if (company.getUf() != null && !UF.equalsIgnoreCase(company.getUf().strip())) {
            throw new BadRequestException(messageService.getMessage("error.fronteira.company.not.pe"));
        }
        return company;
    }

    /** "2026-11", or the current month. */
    public YearMonth periodOrCurrent(String periodo) {
        return periodo == null || periodo.isBlank() ? YearMonth.now() : month(periodo);
    }

    private Company company(String companyId) {
        try {
            return companyValidation.checkExist(UUID.fromString(companyId.strip()));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BadRequestException(messageService.getMessage("error.generic.bad.request"));
        }
    }

    private YearMonth month(String periodo) {
        try {
            return YearMonth.parse(periodo.strip());
        } catch (DateTimeParseException e) {
            throw new BadRequestException(messageService.getMessage("error.generic.bad.request"));
        }
    }

    private static FronteiraDTOs.Extract toDto(EfiscoExtract extract) {
        return new FronteiraDTOs.Extract(extract.getPeriodo(), extract.getNumeroExtrato(), extract.getInscricaoEstadual(),
                extract.getRazaoSocial(), extract.getAre(), extract.getCodigoReceita(), extract.getCredenciamentoAntecipacao(),
                extract.getCredenciamentoInterno(), extract.getDataVencimento(), extract.getEmitidoEm(),
                extract.getIcmsARecolher(), extract.getIcmsPago(), extract.getIcmsEmProcesso(), extract.getIcmsAutuado(),
                extract.getIcmsMandadoSeguranca(), extract.getIcmsAnistiado(), extract.getIcmsRegDebito(),
                extract.getFileName(), extract.getImportedAt(), extract.getImportedBy());
    }
}
