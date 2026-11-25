package br.com.cshub.taxify.service.certidao;

import br.com.cshub.taxify.domain.certidao.CertidaoEstadual;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.enumerated.CertidaoSituacao;
import br.com.cshub.taxify.enumerated.PendencySeverity;
import br.com.cshub.taxify.enumerated.PendencyType;
import br.com.cshub.taxify.repository.CertidaoEstadualRepository;
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

class CertidaoPendenciesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 24);
    private final Company company = new Company(UUID.randomUUID());

    private static CertidaoEstadual certidao(CertidaoSituacao situacao, LocalDate validaAte) {
        CertidaoEstadual certidao = new CertidaoEstadual();
        certidao.setId(UUID.randomUUID());
        certidao.setNumero("2026.000123456789-01");
        certidao.setSituacao(situacao);
        certidao.setValidaAte(validaAte);
        return certidao;
    }

    @Test
    void positiveIsAnAltaPendency() {
        CertidaoEstadual positive = certidao(CertidaoSituacao.POSITIVA, TODAY.plusDays(80));

        List<PendencyFinder.Finding> findings = CertidaoPendencies.findings(company, positive, TODAY);

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.type()).isEqualTo(PendencyType.CERTIDAO_PE_POSITIVA);
            assertThat(finding.severity()).isEqualTo(PendencySeverity.ALTA);
            assertThat(finding.naturalKey()).isEqualTo("CERTIDAO_PE_POSITIVA:" + positive.getId());
            assertThat(finding.objectId()).isEqualTo(company.getId().toString());
        });
    }

    @Test
    void positiveWithNegativeEffectIsOnlyWatched() {
        List<PendencyFinder.Finding> findings = CertidaoPendencies.findings(company,
                certidao(CertidaoSituacao.POSITIVA_EFEITO_NEGATIVA, TODAY.plusDays(80)), TODAY);

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.type()).isEqualTo(PendencyType.CERTIDAO_PE_POSITIVA);
            assertThat(finding.severity()).isEqualTo(PendencySeverity.BAIXA);
        });
    }

    @Test
    void validNegativeHasNoPendency() {
        assertThat(CertidaoPendencies.findings(company, certidao(CertidaoSituacao.NEGATIVA, TODAY.plusDays(16)), TODAY)).isEmpty();
        assertThat(CertidaoPendencies.findings(company, null, TODAY)).isEmpty();
    }

    @Test
    void expiringIn15DaysOrLess() {
        List<PendencyFinder.Finding> findings = CertidaoPendencies.findings(company,
                certidao(CertidaoSituacao.NEGATIVA, TODAY.plusDays(15)), TODAY);

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.type()).isEqualTo(PendencyType.CERTIDAO_PE_VENCENDO);
            assertThat(finding.severity()).isEqualTo(PendencySeverity.MEDIA);
            assertThat(finding.title()).contains("15 dia(s)");
        });
    }

    @Test
    void expiredPositiveGivesBoth() {
        List<PendencyFinder.Finding> findings = CertidaoPendencies.findings(company,
                certidao(CertidaoSituacao.POSITIVA, TODAY.minusDays(1)), TODAY);

        assertThat(findings).extracting(PendencyFinder.Finding::type)
                .containsExactly(PendencyType.CERTIDAO_PE_POSITIVA, PendencyType.CERTIDAO_PE_VENCIDA);
        assertThat(findings.get(1).severity()).isEqualTo(PendencySeverity.ALTA);
    }

    @Test
    void onlyCompaniesOfPernambuco() {
        CertidaoEstadualRepository repository = mock(CertidaoEstadualRepository.class);
        CertidaoPendencies pendencies = new CertidaoPendencies(repository);

        company.setUf("SP");
        assertThat(pendencies.find(company)).isEmpty();
        verifyNoInteractions(repository);

        company.setUf("pe ");
        pendencies.find(company);
        verify(repository).findFirstByCompany_IdAndUfAndDeletedIsFalseOrderByValidaAteDescImportedAtDesc(any(), any());
    }
}
