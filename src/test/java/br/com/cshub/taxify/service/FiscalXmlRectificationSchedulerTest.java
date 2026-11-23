package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.document.Document;
import br.com.cshub.taxify.domain.plan.Plan;
import br.com.cshub.taxify.domain.subscription.Subscription;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
import br.com.cshub.taxify.domain.xml.FiscalXmlSyncControl;
import br.com.cshub.taxify.dto.fiscalrule.FiscalXmlRectificationResultDTO;
import br.com.cshub.taxify.enumerated.DocumentTypeEnum;
import br.com.cshub.taxify.enumerated.PlanServiceEnum;
import br.com.cshub.taxify.integration.fiscalxml.FiscalXmlRectificationProperties;
import br.com.cshub.taxify.repository.FiscalXmlDocumentRepository;
import br.com.cshub.taxify.repository.FiscalXmlSyncControlRepository;
import br.com.cshub.taxify.repository.ProductFiscalRuleRepository;
import br.com.cshub.taxify.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FiscalXmlRectificationSchedulerTest {

    @Mock
    private FiscalXmlSyncControlRepository syncControlRepository;
    @Mock
    private FiscalXmlDocumentRepository documentRepository;
    @Mock
    private ProductFiscalRuleRepository ruleRepository;
    @Mock
    private SubscriptionRepository subscriptionRepository;
    @Mock
    private FiscalXmlRectificationService rectificationService;

    private FiscalXmlRectificationProperties properties;
    private FiscalXmlRectificationScheduler scheduler;

    @BeforeEach
    void setUp() {
        properties = new FiscalXmlRectificationProperties();
        scheduler = new FiscalXmlRectificationScheduler(syncControlRepository, documentRepository, ruleRepository,
                subscriptionRepository, rectificationService, properties);
        when(ruleRepository.findMaxLastModifiedDateByCompanyId(any())).thenReturn(Optional.empty());
        when(syncControlRepository.findAllWithCompany()).thenReturn(List.of());
        when(documentRepository.findByCompany_IdAndIssueDateBetweenOrderByIssueDateDesc(any(), any(), any())).thenReturn(List.of());
    }

    @Test
    void doesNothingWhenDisabled() {
        properties.setEnabled(false);

        scheduler.rectifyAllCompanies();

        verifyNoInteractions(syncControlRepository, documentRepository, rectificationService);
    }

    @Test
    void aSefazOnlyCompanyWithRulesIsRectifiedToo() {
        Company sefazOnly = entitledCompanyWithRules(PlanServiceEnum.FISCAL_XML_IMPORT, PlanServiceEnum.SEFAZ_INTEGRATION);

        assertThat(scheduler.candidates()).containsExactly(sefazOnly);
    }

    @Test
    void aCompanyWithoutTheImportServiceOrWithoutSubscriptionIsLeftOut() {
        Company withoutService = companyWith();
        Company withoutSubscription = companyWith();
        when(ruleRepository.findCompaniesWithRules()).thenReturn(List.of(withoutService, withoutSubscription));
        subscribe(withoutService, PlanServiceEnum.SIEG_INTEGRATION);

        assertThat(scheduler.candidates()).isEmpty();
    }

    @Test
    void aCompanyStillMidSiegImportWaits() {
        Company midImport = entitledCompanyWithRules(PlanServiceEnum.FISCAL_XML_IMPORT, PlanServiceEnum.SIEG_INTEGRATION);
        when(syncControlRepository.findAllWithCompany()).thenReturn(List.of(controlFor(midImport, 200, 100)));

        scheduler.rectifyAllCompanies();

        verifyNoInteractions(documentRepository, rectificationService);
    }

    @Test
    void aCompanyFullySyncedOrNeverCheckedAgainstSiegGoes() {
        Company fullySynced = entitledCompanyWithRules(PlanServiceEnum.FISCAL_XML_IMPORT, PlanServiceEnum.SIEG_INTEGRATION);
        Company neverChecked = companyWith();
        subscribe(neverChecked, PlanServiceEnum.FISCAL_XML_IMPORT, PlanServiceEnum.SEFAZ_INTEGRATION);
        when(ruleRepository.findCompaniesWithRules()).thenReturn(List.of(fullySynced, neverChecked));
        when(syncControlRepository.findAllWithCompany())
                .thenReturn(List.of(controlFor(fullySynced, 200, 200), controlFor(neverChecked, 0, 0)));

        assertThat(scheduler.candidates()).containsExactly(fullySynced, neverChecked);
    }

    @Test
    void rectifiesEveryDocumentReturnedForAnEligibleCompany() {
        Company company = entitledCompanyWithRules(PlanServiceEnum.FISCAL_XML_IMPORT, PlanServiceEnum.SIEG_INTEGRATION);
        FiscalXmlDocument docOne = new FiscalXmlDocument();
        docOne.setId(UUID.randomUUID());
        FiscalXmlDocument docTwo = new FiscalXmlDocument();
        docTwo.setId(UUID.randomUUID());
        when(documentRepository.findByCompany_IdAndIssueDateBetweenOrderByIssueDateDesc(eq(company.getId()), any(), any()))
                .thenReturn(List.of(docOne, docTwo));
        when(rectificationService.rectify(any())).thenReturn(new FiscalXmlRectificationResultDTO(null, null, null, null, List.of()));

        scheduler.rectifyAllCompanies();

        verify(rectificationService).rectify(docOne.getId());
        verify(rectificationService).rectify(docTwo.getId());
    }

    @Test
    void oneFailingDocumentDoesNotStopTheRest() {
        Company company = entitledCompanyWithRules(PlanServiceEnum.FISCAL_XML_IMPORT, PlanServiceEnum.SIEG_INTEGRATION);
        FiscalXmlDocument failing = new FiscalXmlDocument();
        failing.setId(UUID.randomUUID());
        FiscalXmlDocument ok = new FiscalXmlDocument();
        ok.setId(UUID.randomUUID());
        when(documentRepository.findByCompany_IdAndIssueDateBetweenOrderByIssueDateDesc(eq(company.getId()), any(), any()))
                .thenReturn(List.of(failing, ok));
        when(rectificationService.rectify(failing.getId())).thenThrow(new RuntimeException("boom"));
        when(rectificationService.rectify(ok.getId())).thenReturn(new FiscalXmlRectificationResultDTO(null, null, null, null, List.of()));

        scheduler.rectifyAllCompanies();

        verify(rectificationService).rectify(ok.getId());
    }

    @Test
    void oneFailingCompanyDoesNotStopTheRest() {
        Company failingCompany = companyWith();
        Company okCompany = companyWith();
        subscribe(failingCompany, PlanServiceEnum.FISCAL_XML_IMPORT, PlanServiceEnum.SIEG_INTEGRATION);
        subscribe(okCompany, PlanServiceEnum.FISCAL_XML_IMPORT, PlanServiceEnum.SIEG_INTEGRATION);
        when(ruleRepository.findCompaniesWithRules()).thenReturn(List.of(failingCompany, okCompany));
        when(documentRepository.findByCompany_IdAndIssueDateBetweenOrderByIssueDateDesc(eq(failingCompany.getId()), any(), any()))
                .thenThrow(new RuntimeException("boom"));

        scheduler.rectifyAllCompanies();

        verify(documentRepository).findByCompany_IdAndIssueDateBetweenOrderByIssueDateDesc(eq(okCompany.getId()), any(), any());
    }

    private Company entitledCompanyWithRules(PlanServiceEnum... services) {
        Company company = companyWith();
        subscribe(company, services);
        when(ruleRepository.findCompaniesWithRules()).thenReturn(List.of(company));
        return company;
    }

    private void subscribe(Company company, PlanServiceEnum... services) {
        Plan plan = new Plan(UUID.randomUUID());
        plan.setServices(Set.of(services));
        Subscription subscription = new Subscription(UUID.randomUUID());
        subscription.setPlan(plan);
        when(subscriptionRepository.findFirstByCompanyIdAndDeletedIsFalseAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateDesc(
                eq(company.getId()), any(), any())).thenReturn(Optional.of(subscription));
    }

    private FiscalXmlSyncControl controlFor(Company company, int totalSieg, int totalImportado) {
        FiscalXmlSyncControl control = new FiscalXmlSyncControl();
        control.setCompany(company);
        control.setTotalSieg(totalSieg);
        control.setTotalImportado(totalImportado);
        return control;
    }

    private Company companyWith() {
        Company company = new Company(UUID.randomUUID());
        company.setDocument(new Document("11594485000105", DocumentTypeEnum.CNPJ));
        return company;
    }
}
