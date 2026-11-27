package br.com.cshub.taxify.service.fronteira;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.fronteira.FronteiraRule;
import br.com.cshub.taxify.enumerated.FronteiraRuleKind;
import br.com.cshub.taxify.repository.FronteiraRuleRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The extract's credenciamento with no carga líquida in the table: without it every note fell to the regime normal and
 * showed "cobrou a menos" (NF 473: 204,00 against the 132,00 charged). The import creates it, "a conferir".
 */
class FronteiraCargaLiquidaDefaultTest {

    private final FronteiraRuleRepository repository = mock(FronteiraRuleRepository.class);
    private final FronteiraRuleService service = new FronteiraRuleService(repository, null, null, null, null);
    private final Company company = new Company(UUID.randomUUID());

    @Test
    void createsTheCompanysRuleWithTheExtractsRate() {
        when(repository.findAnyOfKind(FronteiraRuleKind.SIMPLES_PERCENTUAL, company.getId())).thenReturn(List.of());

        service.ensureCargaLiquida(company, "TECIDOS E ARMARINHO (INDUSTRIA)", new BigDecimal("5.5"), "10789028-3");

        ArgumentCaptor<FronteiraRule> saved = ArgumentCaptor.forClass(FronteiraRule.class);
        verify(repository).save(saved.capture());
        FronteiraRule rule = saved.getValue();
        assertThat(rule.getCompany()).isSameAs(company);
        assertThat(rule.getKind()).isEqualTo(FronteiraRuleKind.SIMPLES_PERCENTUAL);
        assertThat(rule.getDescription()).isEqualTo("TECIDOS E ARMARINHO (INDUSTRIA)");
        assertThat(rule.getPercentage()).isEqualByComparingTo("5.5");
        assertThat(rule.isReviewed()).isFalse();
        assertThat(rule.getLegalBasis()).contains("5,5%").contains("10789028-3").contains("a conferir");
    }

    /** One already there - global or the company's, written another way, or deleted on purpose - is left alone. */
    @Test
    void anExistingRuleEvenDeletedIsNotRecreated() {
        FronteiraRule deleted = new FronteiraRule();
        deleted.setKind(FronteiraRuleKind.SIMPLES_PERCENTUAL);
        deleted.setDescription("Tecidos e armarinho (indústria)");
        deleted.setDeleted(true);
        when(repository.findAnyOfKind(FronteiraRuleKind.SIMPLES_PERCENTUAL, company.getId())).thenReturn(List.of(deleted));

        service.ensureCargaLiquida(company, "TECIDOS E ARMARINHO (INDUSTRIA)", new BigDecimal("5.5"), "1");

        verify(repository, never()).save(any());
    }

    @Test
    void noCredenciamentoOrNoRateCreatesNothing() {
        service.ensureCargaLiquida(company, null, new BigDecimal("5.5"), "1");
        service.ensureCargaLiquida(company, "TECIDOS", null, "1");

        verify(repository, never()).save(any());
    }
}
