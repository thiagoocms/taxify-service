package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.certificate.CompanyCertificate;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.document.Document;
import br.com.cshub.taxify.domain.plan.Plan;
import br.com.cshub.taxify.domain.subscription.Subscription;
import br.com.cshub.taxify.enumerated.DocumentTypeEnum;
import br.com.cshub.taxify.enumerated.PlanServiceEnum;
import br.com.cshub.taxify.integration.sefaz.SefazProperties;
import br.com.cshub.taxify.repository.CompanyCertificateRepository;
import br.com.cshub.taxify.repository.CompanyRepository;
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
import static org.mockito.Mockito.when;

/** Who enters the SEFAZ capture: note import on the plan + SEFAZ as a source + certificate + covered UF. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SefazDistSchedulerTest {

    @Mock
    private CompanyCertificateRepository certificateRepository;
    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private SubscriptionRepository subscriptionRepository;
    @Mock
    private SefazDistService service;

    private final AccountingFirm firm = new AccountingFirm(UUID.randomUUID());
    private final SefazProperties properties = new SefazProperties();
    private SefazDistScheduler scheduler;
    private Company headOffice;
    private Company branch;

    @BeforeEach
    void setUp() {
        scheduler = new SefazDistScheduler(certificateRepository, companyRepository, subscriptionRepository, service, properties);
        headOffice = company("11222333000181", "PE");
        branch = company("11222333000262", "PE");
        CompanyCertificate certificate = new CompanyCertificate();
        certificate.setCompany(headOffice);
        when(certificateRepository.findAllWithCompany()).thenReturn(List.of(certificate));
        when(companyRepository.findByAccountingFirm_IdAndDeletedIsFalseOrderByNameAsc(firm.getId()))
                .thenReturn(List.of(headOffice, branch, company("99888777000100", "PE")));
        plan(headOffice, PlanServiceEnum.FISCAL_XML_IMPORT, PlanServiceEnum.SEFAZ_INTEGRATION);
        plan(branch, PlanServiceEnum.FISCAL_XML_IMPORT, PlanServiceEnum.SIEG_INTEGRATION, PlanServiceEnum.SEFAZ_INTEGRATION);
    }

    private Company company(String cnpj, String uf) {
        Company company = new Company(UUID.randomUUID());
        company.setDocument(new Document(cnpj, DocumentTypeEnum.CNPJ));
        company.setUf(uf);
        company.setAccountingFirm(firm);
        return company;
    }

    private void plan(Company company, PlanServiceEnum... services) {
        Plan plan = new Plan(UUID.randomUUID());
        plan.setServices(Set.of(services));
        Subscription subscription = new Subscription(UUID.randomUUID());
        subscription.setPlan(plan);
        when(subscriptionRepository.findFirstByCompanyIdAndDeletedIsFalseAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateDesc(
                eq(company.getId()), any(), any())).thenReturn(Optional.of(subscription));
    }

    @Test
    void importPlusSefazAsSourceWithACertificateOfTheRoot() {
        // the third company has no certificate of its root and no plan
        assertThat(scheduler.candidates()).containsExactlyInAnyOrder(headOffice, branch);
    }

    @Test
    void siegOnlyOrNoImportServiceStaysOut() {
        plan(headOffice, PlanServiceEnum.FISCAL_XML_IMPORT, PlanServiceEnum.SIEG_INTEGRATION);
        plan(branch, PlanServiceEnum.SEFAZ_INTEGRATION);

        assertThat(scheduler.candidates()).isEmpty();
    }

    @Test
    void onlyTheCoveredUfs() {
        properties.setUfs(List.of("PE"));
        branch.setUf("SP");

        assertThat(scheduler.candidates()).containsExactly(headOffice);
    }
}
