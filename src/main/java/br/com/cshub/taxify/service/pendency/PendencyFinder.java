package br.com.cshub.taxify.service.pendency;

import br.com.cshub.taxify.domain.certificate.CompanyCertificate;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.pendency.Pendency;
import br.com.cshub.taxify.domain.plan.Plan;
import br.com.cshub.taxify.domain.subscription.Subscription;
import br.com.cshub.taxify.domain.fiscaldocument.DocumentSourceKey;
import br.com.cshub.taxify.domain.fiscaldocument.GoodsInvoice;
import br.com.cshub.taxify.domain.xml.SefazDistControl;
import br.com.cshub.taxify.domain.xml.SefazNoteSummary;
import br.com.cshub.taxify.dto.fiscalxml.ManifestationDTOs;
import br.com.cshub.taxify.enumerated.PendencySeverity;
import br.com.cshub.taxify.enumerated.PendencyType;
import br.com.cshub.taxify.enumerated.PlanServiceEnum;
import br.com.cshub.taxify.enumerated.ReviewStatus;
import br.com.cshub.taxify.integration.sefaz.SefazProperties;
import br.com.cshub.taxify.repository.CompanyTaxRegimeRepository;
import br.com.cshub.taxify.repository.GoodsInvoiceRepository;
import br.com.cshub.taxify.repository.SefazDistControlRepository;
import br.com.cshub.taxify.repository.SefazNoteSummaryRepository;
import br.com.cshub.taxify.repository.SubscriptionRepository;
import br.com.cshub.taxify.service.CompanyCertificateService;
import br.com.cshub.taxify.service.FiscalXmlQueryService;
import br.com.cshub.taxify.service.SefazManifestationScreenService;
import br.com.cshub.taxify.service.taxclearance.TaxClearancePendencies;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What is missing in one company right now, from what Taxify already knows: certificate, SEFAZ
 * capture, ciência, manifestation deadline, SIEG x SEFAZ, divergent notes and regime. Pure
 * reading - {@link PendencyService#sync} turns the findings into pendencies.
 */
@Component
public class PendencyFinder {

    /** The types this finder is responsible for - only these are closed when their cause is gone. */
    public static final Set<PendencyType> MANAGED = EnumSet.complementOf(EnumSet.of(PendencyType.AUDIT_FINDING));

    static final int CERTIFICATE_WARNING_DAYS = 30;
    static final int MANIFESTATION_WARNING_DAYS = 15;
    /** Per type and company: a flood of the same thing is one screen of rows, not thousands. */
    static final int MAX_PER_TYPE = 200;
    private static final int DIVERGENT_MONTHS = 12;
    private static final int SEFAZ_WINDOW_DAYS = 90;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final CompanyCertificateService certificateService;
    private final SefazDistControlRepository controlRepository;
    private final SefazNoteSummaryRepository summaryRepository;
    private final GoodsInvoiceRepository documentRepository;
    private final CompanyTaxRegimeRepository regimeRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final SefazManifestationScreenService manifestationService;
    private final SefazProperties sefazProperties;
    private final br.com.cshub.taxify.service.retention.RetentionCalculator retentionCalculator;
    private final br.com.cshub.taxify.repository.NfseAdnControlRepository adnControlRepository;
    private final TaxClearancePendencies taxClearancePendencies;

    public PendencyFinder(CompanyCertificateService certificateService, SefazDistControlRepository controlRepository,
                          SefazNoteSummaryRepository summaryRepository, GoodsInvoiceRepository documentRepository,
                          CompanyTaxRegimeRepository regimeRepository, SubscriptionRepository subscriptionRepository,
                          SefazManifestationScreenService manifestationService, SefazProperties sefazProperties,
                          br.com.cshub.taxify.service.retention.RetentionCalculator retentionCalculator,
                          br.com.cshub.taxify.repository.NfseAdnControlRepository adnControlRepository,
                          TaxClearancePendencies taxClearancePendencies) {
        this.adnControlRepository = adnControlRepository;
        this.taxClearancePendencies = taxClearancePendencies;
        this.retentionCalculator = retentionCalculator;
        this.certificateService = certificateService;
        this.controlRepository = controlRepository;
        this.summaryRepository = summaryRepository;
        this.documentRepository = documentRepository;
        this.regimeRepository = regimeRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.manifestationService = manifestationService;
        this.sefazProperties = sefazProperties;
    }

    /** One cause found: becomes (or keeps) a pendency with this natural key. */
    public record Finding(PendencyType type, PendencySeverity severity, String naturalKey, String competence,
                          String objectType, String objectId, String title, String detail) {
    }

    @Transactional(readOnly = true)
    public List<Finding> find(Company company) {
        List<Finding> findings = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        Set<PlanServiceEnum> services = servicesOf(company);
        boolean sefaz = PlanServiceEnum.importsFrom(services, PlanServiceEnum.SEFAZ_INTEGRATION);
        boolean sieg = PlanServiceEnum.importsFrom(services, PlanServiceEnum.SIEG_INTEGRATION);
        boolean manifests = sefazProperties.isAutoCiencia() && PlanServiceEnum.manifests(services);

        certificate(company, sefaz, now, findings);
        if (sefaz) {
            capture(company, findings);
            if (manifests) {
                ciencia(company, findings);
                manifestation(company, findings);
            }
            if (sieg) {
                sources(company, now, findings);
            }
        }
        if (PlanServiceEnum.importsFrom(services, PlanServiceEnum.NFSE_NATIONAL_INTEGRATION)) {
            nfseCapture(company, findings);
        }
        divergent(company, now, findings);
        regime(company, findings);
        retentions(company, findings);
        findings.addAll(taxClearancePendencies.find(company));
        return findings;
    }

    private void certificate(Company company, boolean sefaz, LocalDateTime now, List<Finding> findings) {
        CompanyCertificate certificate = certificateService.resolve(company).orElse(null);
        if (certificate == null || certificate.getValidUntil() == null) {
            return;
        }
        String owner = certificate.getCompany() != null && !certificate.getCompany().getId().equals(company.getId())
                ? " (certificado de " + certificate.getCompany().getName() + ", mesma raiz de CNPJ)" : "";
        String impact = sefaz ? " A captura e a manifestação pela SEFAZ param sem ele." : "";
        if (certificate.getValidUntil().isBefore(now)) {
            findings.add(new Finding(PendencyType.CERTIFICATE_EXPIRED, PendencyType.CERTIFICATE_EXPIRED.defaultSeverity,
                    key(PendencyType.CERTIFICATE_EXPIRED, certificate.getId()), null, Pendency.OBJECT_COMPANY,
                    company.getId().toString(),
                    "Certificado A1 vencido em " + certificate.getValidUntil().format(DAY),
                    "Envie um certificado novo no cadastro da empresa" + owner + "." + impact));
        } else if (certificate.getValidUntil().isBefore(now.plusDays(CERTIFICATE_WARNING_DAYS))) {
            long days = ChronoUnit.DAYS.between(now.toLocalDate(), certificate.getValidUntil().toLocalDate());
            findings.add(new Finding(PendencyType.CERTIFICATE_EXPIRING, PendencyType.CERTIFICATE_EXPIRING.defaultSeverity,
                    key(PendencyType.CERTIFICATE_EXPIRING, certificate.getId()), null, Pendency.OBJECT_COMPANY,
                    company.getId().toString(),
                    "Certificado A1 vence em " + days + " dia(s) (" + certificate.getValidUntil().format(DAY) + ")",
                    "Renove e envie o certificado novo no cadastro da empresa" + owner + "." + impact));
        }
    }

    private void capture(Company company, List<Finding> findings) {
        SefazDistControl control = controlRepository.findByCompany_Id(company.getId()).orElse(null);
        if (control == null || control.getLastStatus() != SefazDistControl.Status.ERRO) {
            return;
        }
        findings.add(new Finding(PendencyType.SEFAZ_CAPTURE_ERROR, PendencyType.SEFAZ_CAPTURE_ERROR.defaultSeverity,
                key(PendencyType.SEFAZ_CAPTURE_ERROR, company.getId()), null, Pendency.OBJECT_COMPANY, company.getId().toString(),
                "Captura pela SEFAZ falhando",
                (control.getLastRunAt() == null ? "" : "Última tentativa em " + control.getLastRunAt().format(DAY) + ": ")
                        + (control.getLastDetail() == null ? "sem detalhe." : control.getLastDetail())));
    }

    private void nfseCapture(Company company, List<Finding> findings) {
        var control = adnControlRepository.findByCompany_Id(company.getId()).orElse(null);
        if (control == null || control.getLastStatus() != br.com.cshub.taxify.domain.xml.NfseAdnControl.Status.ERRO) {
            return;
        }
        findings.add(new Finding(PendencyType.NFSE_CAPTURE_ERROR, PendencyType.NFSE_CAPTURE_ERROR.defaultSeverity,
                key(PendencyType.NFSE_CAPTURE_ERROR, company.getId()), null, Pendency.OBJECT_COMPANY, company.getId().toString(),
                "Captura de NFS-e pelo Ambiente Nacional falhando",
                (control.getLastRunAt() == null ? "" : "Última tentativa em " + control.getLastRunAt().format(DAY) + ": ")
                        + (control.getLastDetail() == null ? "sem detalhe." : control.getLastDetail())));
    }

    private void ciencia(Company company, List<Finding> findings) {
        for (SefazNoteSummary summary : summaryRepository.findByCompany_IdAndCompletedAtIsNullAndCienciaStatus(company.getId(),
                SefazNoteSummary.CienciaStatus.ERRO, limit())) {
            findings.add(new Finding(PendencyType.CIENCIA_REFUSED, PendencyType.CIENCIA_REFUSED.defaultSeverity,
                    key(PendencyType.CIENCIA_REFUSED, summary.getAccessKey()), competence(summary.getIssueDate()),
                    Pendency.OBJECT_NOTE_KEY, summary.getAccessKey(),
                    "Ciência recusada - nota de " + nameOr(summary.getIssuerName()) + money(summary.getTotalValue()),
                    (summary.getCienciaDetail() != null ? summary.getCienciaDetail() : summary.getFetchDetail())
                            + " - sem o XML a nota não entra nas regras. Reenvie a ciência ou importe o XML pelo SIEG/upload."));
        }
    }

    private void manifestation(Company company, List<Finding> findings) {
        int count = 0;
        for (ManifestationDTOs.Note note : manifestationService.notesOf(company)) {
            if (note.prazo() == null || note.diasRestantes() > MANIFESTATION_WARNING_DAYS || note.diasRestantes() < 0) {
                continue;
            }
            if (++count > MAX_PER_TYPE) {
                break;
            }
            findings.add(new Finding(PendencyType.MANIFESTATION_DEADLINE, PendencyType.MANIFESTATION_DEADLINE.defaultSeverity,
                    key(PendencyType.MANIFESTATION_DEADLINE, note.chave()), competence(note.dataEmissao()),
                    note.notaId() != null ? Pendency.OBJECT_NOTE : Pendency.OBJECT_NOTE_KEY,
                    note.notaId() != null ? note.notaId().toString() : note.chave(),
                    "Manifestação vence em " + note.diasRestantes() + " dia(s) - NF " + note.numero() + " de "
                            + nameOr(note.emitenteNome()),
                    "Prazo até " + note.prazo().format(DAY) + ". Confirme, desconheça ou informe operação não realizada"
                            + " na tela de Manifestação."));
        }
    }

    private void sources(Company company, LocalDateTime now, List<Finding> findings) {
        LocalDateTime cutoff = now.minusHours(FiscalXmlQueryService.SOURCE_COMPARISON_HOURS);
        for (GoodsInvoice document : documentRepository.findOnlySieg(company.getId(), cutoff,
                now.minusDays(SEFAZ_WINDOW_DAYS), DocumentSourceKey.SIEG, DocumentSourceKey.SEFAZ_DFE, limit())) {
            findings.add(noteFinding(PendencyType.SOURCE_ONLY_SIEG, document,
                    "Nota só no SIEG - NF " + document.getNumber() + " de " + nameOr(document.getIssuer().name()),
                    "O SIEG trouxe a nota há mais de 48h e a SEFAZ não entregou. Confira se o certificado é da"
                            + " empresa destinatária e se a captura pela SEFAZ está em dia."));
        }
        for (GoodsInvoice document : documentRepository.findOnlySefaz(company.getId(), cutoff, DocumentSourceKey.SIEG,
                DocumentSourceKey.SEFAZ_DFE, limit())) {
            findings.add(noteFinding(PendencyType.SOURCE_ONLY_SEFAZ, document,
                    "Nota só na SEFAZ - NF " + document.getNumber() + " de " + nameOr(document.getIssuer().name()),
                    "A SEFAZ entregou a nota há mais de 48h e o SIEG não trouxe. Confira a sincronização do SIEG."));
        }
    }

    private void divergent(Company company, LocalDateTime now, List<Finding> findings) {
        for (GoodsInvoice document : documentRepository.findReviewedSince(
                        company.getId(), ReviewStatus.DIVERGENT, now.minusMonths(DIVERGENT_MONTHS), limit())) {
            findings.add(noteFinding(PendencyType.NOTE_DIVERGENT, document,
                    "Nota com divergência - NF " + document.getNumber() + " de " + nameOr(document.getIssuer().name()),
                    document.getReviewNote() != null && !document.getReviewNote().isBlank()
                            ? "Conferência: " + document.getReviewNote()
                            : "Conferida com divergência - corrija (regra, retificação) e marque como conferida."));
        }
    }

    /** The retention problems (M1) of the current and the previous competence. */
    private void retentions(Company company, List<Finding> findings) {
        java.time.YearMonth current = java.time.YearMonth.now();
        for (java.time.YearMonth month : List.of(current.minusMonths(1), current)) {
            int count = 0;
            for (var issue : retentionCalculator.compute(List.of(company), month).issues()) {
                if (++count > MAX_PER_TYPE) {
                    break;
                }
                boolean undue = br.com.cshub.taxify.service.retention.RetentionCalculator.Issue.UNDUE.equals(issue.kind());
                PendencyType type = undue ? PendencyType.RETENTION_UNDUE : PendencyType.ISS_RETAINED_WITHOUT_VALUE;
                boolean service = "NFSE".equals(issue.documentType());
                findings.add(new Finding(type, type.defaultSeverity,
                        key(type, issue.documentType() + ":" + issue.documentId()), month.toString(),
                        service ? Pendency.OBJECT_SERVICE_NOTE : Pendency.OBJECT_NOTE, issue.documentId().toString(),
                        (undue ? "Retenção provavelmente indevida" : "ISS retido sem valor") + " - "
                                + (service ? "NFS-e " : "NF ") + issue.numero() + " de " + nameOr(issue.counterpartName()),
                        issue.detail() + (undue ? " Veja a tela de Retenções." : " Confira a nota com o tomador.")));
            }
        }
    }

    private void regime(Company company, List<Finding> findings) {
        if (company.getTaxRegimeType() != null || regimeRepository.existsByCompany_IdAndDeletedIsFalse(company.getId())) {
            return;
        }
        findings.add(new Finding(PendencyType.REGIME_MISSING, PendencyType.REGIME_MISSING.defaultSeverity,
                key(PendencyType.REGIME_MISSING, company.getId()), null, Pendency.OBJECT_COMPANY, company.getId().toString(),
                "Regime tributário não informado",
                "Sem o regime a apuração e as checagens por regime não funcionam. Informe no cadastro da empresa."));
    }

    private static Finding noteFinding(PendencyType type, GoodsInvoice document, String title, String detail) {
        return new Finding(type, type.defaultSeverity, key(type, document.getAccessKey() != null ? document.getAccessKey() : document.getId()),
                competence(document.getIssueDate()), Pendency.OBJECT_NOTE, document.getId().toString(), title, detail);
    }

    private Set<PlanServiceEnum> servicesOf(Company company) {
        LocalDate today = LocalDate.now();
        return subscriptionRepository
                .findFirstByCompanyIdAndDeletedIsFalseAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateDesc(
                        company.getId(), today, today)
                .map(Subscription::getPlan)
                .map(Plan::getServices)
                .map(services -> (Set<PlanServiceEnum>) new java.util.HashSet<>(services))
                .orElse(EnumSet.noneOf(PlanServiceEnum.class));
    }

    private static Pageable limit() {
        return PageRequest.of(0, MAX_PER_TYPE);
    }

    static String key(PendencyType type, Object object) {
        return type.name() + ":" + object;
    }

    private static String competence(LocalDateTime date) {
        return date == null ? null : date.format(MONTH);
    }

    private static String nameOr(String name) {
        return name == null || name.isBlank() ? "emitente não informado" : name;
    }

    private static String money(BigDecimal value) {
        return value == null ? "" : " (" + NumberFormat.getCurrencyInstance(Locale.of("pt", "BR")).format(value) + ")";
    }
}
