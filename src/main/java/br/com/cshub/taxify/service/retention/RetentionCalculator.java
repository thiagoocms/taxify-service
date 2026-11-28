package br.com.cshub.taxify.service.retention;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.fiscaldocument.GoodsInvoice;
import br.com.cshub.taxify.domain.fiscaldocument.Party;
import br.com.cshub.taxify.domain.fiscaldocument.Nfse;
import br.com.cshub.taxify.enumerated.CompanyDocumentRole;
import br.com.cshub.taxify.enumerated.TaxRegime;
import br.com.cshub.taxify.repository.GoodsInvoiceRepository;
import br.com.cshub.taxify.repository.NfseRepository;
import br.com.cshub.taxify.service.CompanyTaxRegimeService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Retentions of a competence (roadmap M1), computed from the notes the companies already have:
 * <ul>
 *   <li>the company took the service / bought (NFS-e TOMADA, NF-e received) and withheld the tax:
 *       A_RECOLHER - ISS to the city, IRRF on DARF 1708, PIS/COFINS/CSLL on DARF 5952, INSS on
 *       EFD-Reinf R-2010 and DCTFWeb; federal due on the 20th of the next month, earlier on a weekend;</li>
 *   <li>the company provided the service / sold and suffered the retention: A_COMPENSAR, with the
 *       action of its regime in the competence;</li>
 *   <li>ICMS-ST on a purchase NF-e: INFORMATIVO (goes to the border check, M8).</li>
 * </ul>
 * IRRF, PIS, COFINS or CSLL withheld from a Simples Nacional provider is flagged as probably undue.
 */
@Component
public class RetentionCalculator {

    public enum Nature { A_RECOLHER, A_COMPENSAR, INFORMATIVO }

    public enum Tax { ISS, IRRF, PIS, COFINS, CSLL, INSS, ICMS_ST }

    /** One retention of one note. {@code key} identifies it for the review: doc type + id + tax + nature. */
    public record Retention(String key, Company company, String documentType, UUID documentId, String numero,
                            LocalDateTime issueDate, YearMonth competence, String counterpartName, String counterpartDocument,
                            Nature nature, Tax tax, BigDecimal amount, BigDecimal serviceValue, LocalDate dueDate,
                            String action, boolean undue) {
    }

    /** Something wrong in a note's retention, for the pendencies. */
    public record Issue(String kind, Company company, String documentType, UUID documentId, String numero,
                        YearMonth competence, String counterpartName, String detail) {
        public static final String UNDUE = "UNDUE";
        public static final String ISS_WITHOUT_VALUE = "ISS_WITHOUT_VALUE";
    }

    public record Result(List<Retention> retentions, List<Issue> issues) {
    }

    private final NfseRepository serviceInvoiceRepository;
    private final GoodsInvoiceRepository documentRepository;
    private final CompanyTaxRegimeService regimeService;

    public RetentionCalculator(NfseRepository serviceInvoiceRepository, GoodsInvoiceRepository documentRepository,
                               CompanyTaxRegimeService regimeService) {
        this.serviceInvoiceRepository = serviceInvoiceRepository;
        this.documentRepository = documentRepository;
        this.regimeService = regimeService;
    }

    @Transactional(readOnly = true)
    public Result compute(Collection<Company> companies, YearMonth month) {
        List<Retention> retentions = new ArrayList<>();
        List<Issue> issues = new ArrayList<>();
        if (companies.isEmpty()) {
            return new Result(retentions, issues);
        }
        Map<UUID, Company> byId = companies.stream().collect(Collectors.toMap(Company::getId, Function.identity(), (a, b) -> a));
        Map<UUID, CompanyTaxRegimeService.RegimeAt> regimes = regimeService.regimesAt(companies, month);

        for (Nfse s : serviceInvoiceRepository.findForRetentions(byId.keySet(), month.atDay(1), month.atEndOfMonth())) {
            Company company = byId.get(s.getCompany().getId());
            fromServiceInvoice(company, s, month, regime(regimes, company), retentions, issues);
        }
        for (GoodsInvoice d : documentRepository.findForRetentions(byId.keySet(), month.atDay(1).atStartOfDay(),
                month.atEndOfMonth().atTime(23, 59, 59))) {
            Company company = byId.get(d.getCompany().getId());
            fromGoodsNote(company, d, month, regime(regimes, company), retentions, issues);
        }
        return new Result(retentions, issues);
    }

    private void fromServiceInvoice(Company company, Nfse s, YearMonth month, TaxRegime regime,
                                    List<Retention> out, List<Issue> issues) {
        boolean took = s.getCompanyRole() == CompanyDocumentRole.TAKER;
        boolean provided = s.getCompanyRole() == CompanyDocumentRole.ISSUER;
        if (!took && !provided) {
            return;
        }
        Nature nature = took ? Nature.A_RECOLHER : Nature.A_COMPENSAR;
        Party counterpart = took ? s.getIssuer() : s.getRecipient();
        Source source = new Source(company, s.legacyKeyLabel(), s.getId(), s.getNumber(), s.getIssueDate(), month,
                counterpart.name(), counterpart.document(), s.getTotalAmount());

        if (s.getIss().isWithheld()) {
            if (positive(s.getIss().withheldAmount())) {
                String city = s.getIncidenceCityCode() == null ? "" : " (IBGE " + s.getIncidenceCityCode() + ")";
                out.add(source.retention(nature, Tax.ISS, s.getIss().withheldAmount(), null,
                        took ? "Guia de ISS do município de incidência" + city + " - vencimento conforme o município."
                                : compensationAction(Tax.ISS, regime), false));
            } else {
                issues.add(new Issue(Issue.ISS_WITHOUT_VALUE, company, s.legacyKeyLabel(), s.getId(), s.getNumber(), month,
                        counterpart.name(),
                        "NFS-e com ISS marcado como retido, mas sem valor de ISS retido."));
            }
        }
        add(source, nature, Tax.IRRF, s.getWithholdings().irrf(), took, regime, out, issues);
        // PIS/COFINS only when the note says they were withheld (Nfse#withheldPis/withheldCofins)
        add(source, nature, Tax.PIS, s.withheldPis(), took, regime, out, issues);
        add(source, nature, Tax.COFINS, s.withheldCofins(), took, regime, out, issues);
        add(source, nature, Tax.CSLL, s.getWithholdings().csll(), took, regime, out, issues);
        add(source, nature, Tax.INSS, s.getWithholdings().inss(), took, regime, out, issues);
    }

    private void fromGoodsNote(Company company, GoodsInvoice d, YearMonth month, TaxRegime regime,
                               List<Retention> out, List<Issue> issues) {
        boolean bought = !d.isOwnIssue();
        Nature nature = bought ? Nature.A_RECOLHER : Nature.A_COMPENSAR;
        Party counterpart = bought ? d.getIssuer() : d.getRecipient();
        // CNPJ or CPF of the counterpart (a foreign id never names one here)
        String counterpartDocument = counterpart.cnpj() != null ? counterpart.cnpj() : counterpart.cpf();
        // legacyKeyLabel: "NFE" also for an NFC-e - the key of the reviews stored today (FD-RN-21)
        Source source = new Source(company, d.legacyKeyLabel(), d.getId(), d.getNumber(), d.getIssueDate(), month,
                counterpart.name(), counterpartDocument, d.getTotalAmount());
        add(source, nature, Tax.IRRF, d.getWithholdings().irrf(), bought, regime, out, issues);
        add(source, nature, Tax.PIS, d.getWithholdings().pis(), bought, regime, out, issues);
        add(source, nature, Tax.COFINS, d.getWithholdings().cofins(), bought, regime, out, issues);
        add(source, nature, Tax.CSLL, d.getWithholdings().csll(), bought, regime, out, issues);
        add(source, nature, Tax.INSS, d.getWithholdings().inss(), bought, regime, out, issues);
        BigDecimal icmsSt = d.getGoodsTotals().icmsStAmount();
        if (bought && positive(icmsSt)) {
            out.add(source.retention(Nature.INFORMATIVO, Tax.ICMS_ST, icmsSt, null,
                    "ICMS-ST da compra - entra na conferência da fronteira.", false));
        }
    }

    /** One federal retention: withheld by the company (to pay) or suffered by it (to offset / undue). */
    private void add(Source source, Nature nature, Tax tax, BigDecimal amount, boolean withheldByCompany, TaxRegime regime,
                     List<Retention> out, List<Issue> issues) {
        if (!positive(amount)) {
            return;
        }
        if (withheldByCompany) {
            out.add(source.retention(nature, tax, amount, federalDueDate(source.competence()), payAction(tax), false));
            return;
        }
        boolean undue = isSimples(regime) && tax != Tax.INSS;
        out.add(source.retention(nature, tax, amount, null,
                undue ? "Provável retenção indevida: optante do Simples Nacional não sofre retenção de " + tax
                        + " (salvo exceções). Confira e peça o ajuste ao tomador." : compensationAction(tax, regime), undue));
        if (undue) {
            issues.add(new Issue(Issue.UNDUE, source.company(), source.documentType(), source.documentId(), source.numero(),
                    source.competence(), source.counterpartName(),
                    "Retenção de " + tax + " de " + amount + " em nota de prestador do Simples Nacional."));
        }
    }

    static String payAction(Tax tax) {
        return switch (tax) {
            case IRRF -> "Recolher em DARF 1708.";
            case PIS, COFINS, CSLL -> "Recolher em DARF 5952 (PIS/COFINS/CSLL).";
            case INSS -> "Informar na EFD-Reinf (R-2010) e recolher pela DCTFWeb.";
            default -> "Recolher.";
        };
    }

    static String compensationAction(Tax tax, TaxRegime regime) {
        if (regime == null) {
            return "Regime da empresa não informado - informe para saber como compensar.";
        }
        if (isSimples(regime)) {
            return switch (tax) {
                case ISS -> "Informar o ISS retido no PGDAS-D.";
                case INSS -> "Compensar na DCTFWeb (EFD-Reinf R-2020).";
                default -> "Optante do Simples: conferir a retenção.";
            };
        }
        return switch (tax) {
            case ISS -> "ISS retido pelo tomador - não recolher de novo sobre esta nota.";
            case IRRF -> "Deduzir do IRPJ do período.";
            case CSLL -> "Deduzir da CSLL do período.";
            case PIS, COFINS -> "Deduzir do " + tax + " do mês.";
            case INSS -> "Compensar na DCTFWeb (EFD-Reinf R-2020).";
            default -> "Compensar.";
        };
    }

    /** Day 20 of the month after the competence; on a weekend, the Friday before (holidays not handled). */
    static LocalDate federalDueDate(YearMonth competence) {
        LocalDate due = competence.plusMonths(1).atDay(20);
        if (due.getDayOfWeek() == DayOfWeek.SATURDAY) {
            return due.minusDays(1);
        }
        if (due.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return due.minusDays(2);
        }
        return due;
    }

    private static boolean isSimples(TaxRegime regime) {
        return regime == TaxRegime.SIMPLES_NACIONAL || regime == TaxRegime.MEI;
    }

    private static TaxRegime regime(Map<UUID, CompanyTaxRegimeService.RegimeAt> regimes, Company company) {
        CompanyTaxRegimeService.RegimeAt at = regimes.get(company.getId());
        return at == null ? company.getTaxRegimeType() : at.regime();
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    /** The note a retention comes from. */
    private record Source(Company company, String documentType, UUID documentId, String numero, LocalDateTime issueDate,
                          YearMonth competence, String counterpartName, String counterpartDocument, BigDecimal serviceValue) {

        Retention retention(Nature nature, Tax tax, BigDecimal amount, LocalDate due, String action, boolean undue) {
            String key = documentType + ":" + documentId + ":" + tax + ":" + nature;
            return new Retention(key, company, documentType, documentId, numero, issueDate, competence, counterpartName,
                    counterpartDocument, nature, tax, amount, serviceValue, due, action, undue);
        }
    }
}
