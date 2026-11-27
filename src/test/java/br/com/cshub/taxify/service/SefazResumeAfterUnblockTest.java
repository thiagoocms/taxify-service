package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.certificate.CompanyCertificate;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.document.Document;
import br.com.cshub.taxify.domain.plan.Plan;
import br.com.cshub.taxify.domain.subscription.Subscription;
import br.com.cshub.taxify.domain.xml.SefazDistControl;
import br.com.cshub.taxify.dto.sieg.XmlDownloadResultDTO;
import br.com.cshub.taxify.enumerated.DocumentTypeEnum;
import br.com.cshub.taxify.enumerated.PlanServiceEnum;
import br.com.cshub.taxify.integration.sefaz.A1Certificate;
import br.com.cshub.taxify.integration.sefaz.SefazDistClient;
import br.com.cshub.taxify.integration.sefaz.SefazDistResponse;
import br.com.cshub.taxify.integration.sefaz.SefazProperties;
import br.com.cshub.taxify.repository.CaptureRunRepository;
import br.com.cshub.taxify.repository.CompanyCertificateRepository;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.repository.SefazDistControlRepository;
import br.com.cshub.taxify.repository.SubscriptionRepository;
import br.com.cshub.taxify.service.firmaccess.FirmAccessService;
import br.com.cshub.taxify.service.firmaccess.RoutineGate;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CT1-21 / CA1-12 (F1-RN-10): ult_nsu = 000000000000500 gravado antes do bloqueio; enquanto bloqueado, nenhuma rodada
 * consulta a SEFAZ nem mexe no controle; depois do desbloqueio a primeira consulta parte do 500 - nada é zerado.
 */
class SefazResumeAfterUnblockTest {

    private static final String CNPJ = "11222333000181";

    @Test
    void theFirstQueryAfterTheUnblockStartsFromTheStoredNsu() throws Exception {
        AccountingFirm firm = new AccountingFirm(UUID.randomUUID());
        Company company = new Company(UUID.randomUUID());
        company.setAccountingFirm(firm);
        company.setDocument(new Document(CNPJ, DocumentTypeEnum.CNPJ));
        company.setUf("PE");

        CompanyCertificateRepository certificateRepository = mock(CompanyCertificateRepository.class);
        CompanyRepository companyRepository = mock(CompanyRepository.class);
        SubscriptionRepository subscriptionRepository = mock(SubscriptionRepository.class);
        CompanyCertificate certificateRow = new CompanyCertificate();
        certificateRow.setCompany(company);
        certificateRow.setValidUntil(LocalDateTime.now().plusYears(1));
        when(certificateRepository.findAllWithCompany()).thenReturn(List.of(certificateRow));
        when(companyRepository.findByAccountingFirm_IdAndDeletedIsFalseOrderByNameAsc(firm.getId())).thenReturn(List.of(company));
        Plan plan = new Plan(UUID.randomUUID());
        plan.setServices(Set.of(PlanServiceEnum.FISCAL_XML_IMPORT, PlanServiceEnum.SEFAZ_INTEGRATION));
        Subscription subscription = new Subscription(UUID.randomUUID());
        subscription.setPlan(plan);
        when(subscriptionRepository.findFirstByCompanyIdAndDeletedIsFalseAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateDesc(
                eq(company.getId()), any(), any())).thenReturn(Optional.of(subscription));

        // the control row as it was when the firm was blocked
        SefazDistControl control = new SefazDistControl();
        control.setCompany(company);
        control.setUltNsu("000000000000500");
        SefazDistControlRepository controlRepository = mock(SefazDistControlRepository.class);
        when(controlRepository.findByCompany_Id(company.getId())).thenReturn(Optional.of(control));
        when(controlRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        CompanyCertificateService certificateService = mock(CompanyCertificateService.class);
        A1Certificate a1 = mock(A1Certificate.class);
        when(certificateService.resolve(company)).thenReturn(Optional.of(certificateRow));
        when(certificateService.open(certificateRow)).thenReturn(a1);
        SefazDistClient client = mock(SefazDistClient.class);
        when(client.fetch(a1, "PE", CNPJ, "000000000000500")).thenReturn(new SefazDistResponse("137", "nada novo",
                "000000000000500", "000000000000500", List.of()));
        FiscalXmlIntegrationService integrationService = mock(FiscalXmlIntegrationService.class);
        lenient().when(integrationService.importFromSefaz(any(), any()))
                .thenReturn(new XmlDownloadResultDTO(0, 0, 0, 0, 0, List.of(), List.of(), 0));
        SefazManifestService manifestService = mock(SefazManifestService.class);
        lenient().when(manifestService.manifestAndFetch(any(), any(), anyString(), anyBoolean())).thenReturn(SefazManifestService.Outcome.NONE);
        SefazProperties properties = new SefazProperties();
        properties.setPauseBetweenBatchesMillis(0);
        SefazDistService service = new SefazDistService(controlRepository, certificateService, integrationService, client,
                properties, manifestService, mock(CaptureRunRepository.class));

        // the firm's situation, switched by the test
        Set<UUID> blocked = new HashSet<>(Set.of(firm.getId()));
        FirmAccessService access = mock(FirmAccessService.class);
        when(access.routineGate(anyString())).thenAnswer(invocation ->
                new RoutineGate(invocation.getArgument(0), firmId -> !blocked.contains(firmId)));
        SefazDistScheduler scheduler = new SefazDistScheduler(certificateRepository, companyRepository, subscriptionRepository,
                service, properties, access);

        // blocked: two rounds, nobody asks SEFAZ and the NSU stays
        scheduler.captureAll();
        scheduler.captureAll();
        verify(client, never()).fetch(any(), anyString(), anyString(), anyString());
        verify(controlRepository, never()).save(any());
        assertThat(control.getUltNsu()).isEqualTo("000000000000500");

        // reactivated: the next round resumes from 500
        blocked.clear();
        scheduler.captureAll();
        verify(client).fetch(a1, "PE", CNPJ, "000000000000500");
        assertThat(control.getUltNsu()).isEqualTo("000000000000500");
    }
}
