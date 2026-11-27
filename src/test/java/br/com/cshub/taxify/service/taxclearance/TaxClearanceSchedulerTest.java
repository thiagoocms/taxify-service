package br.com.cshub.taxify.service.taxclearance;

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

class TaxClearanceSchedulerTest {

    @Test
    void aCaptchaStopsAskingEFiscoForTheOtherCompanies() {
        TaxClearanceService service = mock(TaxClearanceService.class);
        Company first = new Company(UUID.randomUUID());
        Company second = new Company(UUID.randomUUID());
        Company third = new Company(UUID.randomUUID());
        when(service.dueCompanies()).thenReturn(List.of(first, second, third));
        when(service.issue(first)).thenReturn(new TaxClearanceService.Attempt(false, true, "captcha"));

        new TaxClearanceScheduler(service, true, br.com.cshub.taxify.service.firmaccess.FirmAccessTestSupport.allowAll()).runAll();

        verify(service, times(1)).issue(any());
        verify(service).recordSkipped(second, "captcha");
        verify(service).recordSkipped(third, "captcha");
    }

    @Test
    void offDoesNothing() {
        TaxClearanceService service = mock(TaxClearanceService.class);

        new TaxClearanceScheduler(service, false, br.com.cshub.taxify.service.firmaccess.FirmAccessTestSupport.allowAll()).tick();

        verify(service, never()).dueCompanies();
    }
}
