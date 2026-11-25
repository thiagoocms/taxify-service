package br.com.cshub.taxify.service.certidao;

import br.com.cshub.taxify.domain.company.Company;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CertidaoSchedulerTest {

    @Test
    void aCaptchaStopsAskingEFiscoForTheOtherCompanies() {
        CertidaoService service = mock(CertidaoService.class);
        Company first = new Company(UUID.randomUUID());
        Company second = new Company(UUID.randomUUID());
        Company third = new Company(UUID.randomUUID());
        when(service.dueCompanies()).thenReturn(List.of(first, second, third));
        when(service.issue(first)).thenReturn(new CertidaoService.Attempt(false, true, "captcha"));

        new CertidaoScheduler(service, true).runAll();

        verify(service, times(1)).issue(any());
        verify(service).recordSkipped(second, "captcha");
        verify(service).recordSkipped(third, "captcha");
    }

    @Test
    void offDoesNothing() {
        CertidaoService service = mock(CertidaoService.class);

        new CertidaoScheduler(service, false).tick();

        verify(service, never()).dueCompanies();
    }
}
