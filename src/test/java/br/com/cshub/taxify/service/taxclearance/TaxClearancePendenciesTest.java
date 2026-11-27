package br.com.cshub.taxify.service.taxclearance;

import br.com.cshub.taxify.domain.taxclearance.StateTaxClearance;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.enumerated.TaxClearanceStatus;
import br.com.cshub.taxify.enumerated.PendencySeverity;
import br.com.cshub.taxify.enumerated.PendencyType;
import br.com.cshub.taxify.repository.StateTaxClearanceRepository;
import br.com.cshub.taxify.service.pendency.PendencyFinder;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class TaxClearancePendenciesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 24);
    private final Company company = new Company(UUID.randomUUID());

    private static StateTaxClearance clearance(TaxClearanceStatus situacao, LocalDate validaAte) {
        StateTaxClearance clearance = new StateTaxClearance();
        clearance.setId(UUID.randomUUID());
        clearance.setNumber("2026.000123456789-01");
        clearance.setStatus(situacao);
        clearance.setValidUntil(validaAte);
        return clearance;
    }

    @Test
    void positiveIsAnAltaPendency() {
        StateTaxClearance positive = clearance(TaxClearanceStatus.POSITIVA, TODAY.plusDays(80));

        List<PendencyFinder.Finding> findings = TaxClearancePendencies.findings(company, positive, TODAY);

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.type()).isEqualTo(PendencyType.CERTIDAO_PE_POSITIVA);
            assertThat(finding.severity()).isEqualTo(PendencySeverity.ALTA);
            assertThat(finding.naturalKey()).isEqualTo("CERTIDAO_PE_POSITIVA:" + positive.getId());
            assertThat(finding.objectId()).isEqualTo(company.getId().toString());
        });
    }

    @Test
    void positiveWithNegativeEffectIsOnlyWatched() {
        List<PendencyFinder.Finding> findings = TaxClearancePendencies.findings(company,
                clearance(TaxClearanceStatus.POSITIVA_EFEITO_NEGATIVA, TODAY.plusDays(80)), TODAY);

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.type()).isEqualTo(PendencyType.CERTIDAO_PE_POSITIVA);
            assertThat(finding.severity()).isEqualTo(PendencySeverity.BAIXA);
        });
    }

    @Test
    void validNegativeHasNoPendency() {
        assertThat(TaxClearancePendencies.findings(company, clearance(TaxClearanceStatus.NEGATIVA, TODAY.plusDays(16)), TODAY)).isEmpty();
        assertThat(TaxClearancePendencies.findings(company, null, TODAY)).isEmpty();
    }

    @Test
    void expiringIn15DaysOrLess() {
        List<PendencyFinder.Finding> findings = TaxClearancePendencies.findings(company,
                clearance(TaxClearanceStatus.NEGATIVA, TODAY.plusDays(15)), TODAY);

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.type()).isEqualTo(PendencyType.CERTIDAO_PE_VENCENDO);
            assertThat(finding.severity()).isEqualTo(PendencySeverity.MEDIA);
            assertThat(finding.title()).contains("15 dia(s)");
        });
    }

    @Test
    void expiredPositiveGivesBoth() {
        List<PendencyFinder.Finding> findings = TaxClearancePendencies.findings(company,
                clearance(TaxClearanceStatus.POSITIVA, TODAY.minusDays(1)), TODAY);

        assertThat(findings).extracting(PendencyFinder.Finding::type)
                .containsExactly(PendencyType.CERTIDAO_PE_POSITIVA, PendencyType.CERTIDAO_PE_VENCIDA);
        assertThat(findings.get(1).severity()).isEqualTo(PendencySeverity.ALTA);
    }

    @Test
    void onlyCompaniesOfPernambuco() {
        StateTaxClearanceRepository repository = mock(StateTaxClearanceRepository.class);
        TaxClearancePendencies pendencies = new TaxClearancePendencies(repository);

        company.setUf("SP");
        assertThat(pendencies.find(company)).isEmpty();
        verifyNoInteractions(repository);

        company.setUf("pe ");
        pendencies.find(company);
        verify(repository).findFirstByCompany_IdAndUfAndDeletedIsFalseOrderByValidUntilDescImportedAtDesc(any(), any());
    }
}
