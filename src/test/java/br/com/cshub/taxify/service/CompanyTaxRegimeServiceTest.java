package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.company.CompanyTaxRegime;
import br.com.cshub.taxify.enumerated.BusinessActivity;
import br.com.cshub.taxify.enumerated.TaxRegime;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.repository.CompanyTaxRegimeRepository;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompanyTaxRegimeServiceTest {

    private final CompanyTaxRegimeRepository repository = mock(CompanyTaxRegimeRepository.class);
    private final CompanyTaxRegimeService service = new CompanyTaxRegimeService(repository, mock(CompanyRepository.class),
            mock(CompanyValidation.class), mock(MessageService.class));

    private static CompanyTaxRegime period(Company company, TaxRegime regime, LocalDate from) {
        CompanyTaxRegime period = new CompanyTaxRegime();
        period.setId(UUID.randomUUID());
        period.setCompany(company);
        period.setTaxRegime(regime);
        period.setBusinessActivity(BusinessActivity.COMMERCE);
        period.setValidFrom(from);
        return period;
    }

    private static Company company(TaxRegime regime) {
        Company company = new Company(UUID.randomUUID());
        company.setTaxRegimeType(regime);
        company.setBusinessActivity(BusinessActivity.COMMERCE);
        return company;
    }

    @Test
    void eachMonthUsesTheRegimeInForceThen() {
        Company company = company(TaxRegime.LUCRO_PRESUMIDO);
        when(repository.historyOf(any())).thenReturn(List.of(
                period(company, TaxRegime.SIMPLES_NACIONAL, null),
                period(company, TaxRegime.LUCRO_PRESUMIDO, LocalDate.of(2026, 1, 1))));

        Map<UUID, CompanyTaxRegimeService.RegimeAt> december = service.regimesAt(List.of(company), YearMonth.of(2025, 12));
        Map<UUID, CompanyTaxRegimeService.RegimeAt> january = service.regimesAt(List.of(company), YearMonth.of(2026, 1));

        assertThat(december.get(company.getId()).regime()).isEqualTo(TaxRegime.SIMPLES_NACIONAL);
        assertThat(january.get(company.getId()).regime()).isEqualTo(TaxRegime.LUCRO_PRESUMIDO);
    }

    @Test
    void withoutHistoryTheCompanyFieldsAreUsed() {
        Company company = company(TaxRegime.MEI);
        when(repository.historyOf(any())).thenReturn(List.of());

        assertThat(service.regimesAt(List.of(company), YearMonth.of(2026, 3)).get(company.getId()).regime()).isEqualTo(TaxRegime.MEI);
    }

    @Test
    void firstRegimeIsSinceAlways() {
        Company company = company(TaxRegime.SIMPLES_NACIONAL);
        when(repository.history(company.getId())).thenReturn(List.of());

        service.recordChange(company, null);

        ArgumentCaptor<CompanyTaxRegime> saved = ArgumentCaptor.forClass(CompanyTaxRegime.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getValidFrom()).isNull();
        assertThat(saved.getValue().getTaxRegime()).isEqualTo(TaxRegime.SIMPLES_NACIONAL);
    }

    @Test
    void aChangeStartsAPeriodInTheGivenMonth() {
        Company company = company(TaxRegime.LUCRO_PRESUMIDO);
        when(repository.history(company.getId())).thenReturn(List.of(period(company, TaxRegime.SIMPLES_NACIONAL, null)));

        service.recordChange(company, YearMonth.of(2026, 1));

        ArgumentCaptor<CompanyTaxRegime> saved = ArgumentCaptor.forClass(CompanyTaxRegime.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getValidFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(saved.getValue().getTaxRegime()).isEqualTo(TaxRegime.LUCRO_PRESUMIDO);
    }

    @Test
    void sameRegimeAsTheCurrentPeriodIsNoChange() {
        Company company = company(TaxRegime.SIMPLES_NACIONAL);
        when(repository.history(company.getId())).thenReturn(List.of(period(company, TaxRegime.SIMPLES_NACIONAL, null)));

        service.recordChange(company, null);

        verify(repository, never()).save(any());
    }
}
