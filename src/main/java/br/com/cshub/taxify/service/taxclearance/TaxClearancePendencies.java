package br.com.cshub.taxify.service.taxclearance;

import br.com.cshub.taxify.domain.taxclearance.StateTaxClearance;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.pendency.Pendency;
import br.com.cshub.taxify.enumerated.TaxClearanceStatus;
import br.com.cshub.taxify.enumerated.PendencySeverity;
import br.com.cshub.taxify.enumerated.PendencyType;
import br.com.cshub.taxify.repository.StateTaxClearanceRepository;
import br.com.cshub.taxify.service.pendency.PendencyFinder;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The certidão SEFAZ-PE causes of a company's pendencies (read by {@link PendencyFinder}): the newest certidão is
 * positiva (ALTA; positiva com efeito de negativa, BAIXA), expired (ALTA) or expiring in
 * {@value TaxClearanceRenewalRule#RENEW_DAYS} days or less (MEDIA). A company without any certidão yet gets no pendency -
 * the carteira's column shows it - so turning the module on doesn't flood the screen.
 */
@Component
public class TaxClearancePendencies {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final StateTaxClearanceRepository repository;

    public TaxClearancePendencies(StateTaxClearanceRepository repository) {
        this.repository = repository;
    }

    public List<PendencyFinder.Finding> find(Company company) {
        if (!TaxClearanceService.isPernambuco(company)) {
            return List.of();
        }
        StateTaxClearance latest = repository
                .findFirstByCompany_IdAndUfAndDeletedIsFalseOrderByValidUntilDescImportedAtDesc(company.getId(), TaxClearanceService.UF)
                .orElse(null);
        return findings(company, latest, LocalDate.now());
    }

    static List<PendencyFinder.Finding> findings(Company company, StateTaxClearance latest, LocalDate today) {
        List<PendencyFinder.Finding> findings = new ArrayList<>();
        if (latest == null || latest.getValidUntil() == null) {
            return findings;
        }
        String companyId = company.getId().toString();
        String number = latest.getNumber() == null ? "" : " nº " + latest.getNumber();
        if (latest.getStatus() == TaxClearanceStatus.POSITIVA || latest.getStatus() == TaxClearanceStatus.POSITIVA_EFEITO_NEGATIVA) {
            boolean positive = latest.getStatus() == TaxClearanceStatus.POSITIVA;
            findings.add(new PendencyFinder.Finding(PendencyType.CERTIDAO_PE_POSITIVA,
                    positive ? PendencyType.CERTIDAO_PE_POSITIVA.defaultSeverity : PendencySeverity.BAIXA,
                    key(PendencyType.CERTIDAO_PE_POSITIVA, latest), null, Pendency.OBJECT_COMPANY, companyId,
                    "Certidão estadual (PE) " + latest.getStatus().label.toLowerCase(java.util.Locale.ROOT) + number,
                    positive
                            ? "A SEFAZ-PE aponta débitos da empresa: a certidão não serve como negativa (licitação, crédito,"
                            + " cadastro). Veja os débitos no e-Fisco, regularize e emita a certidão de novo."
                            : "Há débitos com a exigibilidade suspensa (parcelamento, impugnação ou garantia). Vale como"
                            + " negativa, mas acompanhe as parcelas para não virar positiva."));
        }
        TaxClearanceRenewalRule.Status status = TaxClearanceRenewalRule.status(latest, today);
        if (status == TaxClearanceRenewalRule.Status.VENCIDA) {
            findings.add(new PendencyFinder.Finding(PendencyType.CERTIDAO_PE_VENCIDA, PendencyType.CERTIDAO_PE_VENCIDA.defaultSeverity,
                    key(PendencyType.CERTIDAO_PE_VENCIDA, latest), null, Pendency.OBJECT_COMPANY, companyId,
                    "Certidão estadual (PE) vencida em " + latest.getValidUntil().format(DAY),
                    "Emita a certidão de novo no e-Fisco e envie o PDF na ficha da empresa (seção Certidão SEFAZ-PE)."));
        } else if (status == TaxClearanceRenewalRule.Status.A_VENCER) {
            long days = TaxClearanceRenewalRule.daysLeft(latest, today);
            findings.add(new PendencyFinder.Finding(PendencyType.CERTIDAO_PE_VENCENDO, PendencyType.CERTIDAO_PE_VENCENDO.defaultSeverity,
                    key(PendencyType.CERTIDAO_PE_VENCENDO, latest), null, Pendency.OBJECT_COMPANY, companyId,
                    "Certidão estadual (PE) vence em " + days + " dia(s) (" + latest.getValidUntil().format(DAY) + ")",
                    "Emita a próxima no e-Fisco e envie o PDF na ficha da empresa (seção Certidão SEFAZ-PE)."));
        }
        return findings;
    }

    private static String key(PendencyType type, StateTaxClearance clearance) {
        return type.name() + ":" + clearance.getId();
    }
}
