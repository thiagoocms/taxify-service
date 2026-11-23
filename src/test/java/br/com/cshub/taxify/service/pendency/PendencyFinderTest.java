package br.com.cshub.taxify.service.pendency;

import br.com.cshub.taxify.domain.certificate.CompanyCertificate;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.plan.Plan;
import br.com.cshub.taxify.domain.subscription.Subscription;
import br.com.cshub.taxify.domain.xml.SefazDistControl;
import br.com.cshub.taxify.dto.fiscalxml.ManifestationDTOs;
import br.com.cshub.taxify.enumerated.PendencyType;
import br.com.cshub.taxify.enumerated.PlanServiceEnum;
import br.com.cshub.taxify.enumerated.TaxRegime;
import br.com.cshub.taxify.integration.sefaz.SefazProperties;
import br.com.cshub.taxify.repository.CompanyTaxRegimeRepository;
import br.com.cshub.taxify.repository.FiscalXmlDocumentRepository;
import br.com.cshub.taxify.repository.SefazDistControlRepository;
import br.com.cshub.taxify.repository.SefazNoteSummaryRepository;
import br.com.cshub.taxify.repository.SubscriptionRepository;
import br.com.cshub.taxify.service.CompanyCertificateService;
import br.com.cshub.taxify.service.SefazManifestationScreenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PendencyFinderTest {

    @Mock
    private CompanyCertificateService certificateService;
    @Mock
    private SefazDistControlRepository controlRepository;
    @Mock
    private SefazNoteSummaryRepository summaryRepository;
    @Mock
    private FiscalXmlDocumentRepository documentRepository;
    @Mock
    private CompanyTaxRegimeRepository regimeRepository;
    @Mock
    private SubscriptionRepository subscriptionRepository;
    @Mock
    private SefazManifestationScreenService manifestationService;

    private PendencyFinder finder;
    private Company company;

    @BeforeEach
    void setUp() {
        finder = new PendencyFinder(certificateService, controlRepository, summaryRepository, documentRepository,
                regimeRepository, subscriptionRepository, manifestationService, new SefazProperties());
        company = new Company(UUID.randomUUID());
        company.setTaxRegimeType(TaxRegime.values()[0]);
        when(certificateService.resolve(company)).thenReturn(Optional.empty());
        when(controlRepository.findByCompany_Id(any())).thenReturn(Optional.empty());
        when(manifestationService.notesOf(company)).thenReturn(List.of());
    }

    private void plan(PlanServiceEnum... services) {
        Plan plan = new Plan(UUID.randomUUID());
        plan.setServices(Set.of(services));
        Subscription subscription = new Subscription(UUID.randomUUID());
        subscription.setPlan(plan);
        when(subscriptionRepository.findFirstByCompanyIdAndDeletedIsFalseAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateDesc(
                eq(company.getId()), any(), any())).thenReturn(Optional.of(subscription));
    }

    private void certificateValidUntil(LocalDateTime until) {
        CompanyCertificate certificate = new CompanyCertificate();
        certificate.setId(UUID.randomUUID());
        certificate.setCompany(company);
        certificate.setValidUntil(until);
        when(certificateService.resolve(company)).thenReturn(Optional.of(certificate));
    }

    private List<PendencyType> types() {
        return finder.find(company).stream().map(PendencyFinder.Finding::type).toList();
    }

    @Test
    void nothingMissingNothingFound() {
        assertThat(finder.find(company)).isEmpty();
    }

    @Test
    void anExpiredOrExpiringCertificate() {
        certificateValidUntil(LocalDateTime.now().minusDays(1));
        assertThat(types()).containsExactly(PendencyType.CERTIFICATE_EXPIRED);

        certificateValidUntil(LocalDateTime.now().plusDays(10));
        List<PendencyFinder.Finding> findings = finder.find(company);
        assertThat(findings).extracting(PendencyFinder.Finding::type).containsExactly(PendencyType.CERTIFICATE_EXPIRING);
        assertThat(findings.getFirst().title()).contains("vence em");

        certificateValidUntil(LocalDateTime.now().plusDays(90));
        assertThat(types()).isEmpty();
    }

    @Test
    void aFailingCaptureOnlyCountsWithSefazInThePlan() {
        SefazDistControl control = new SefazDistControl();
        control.setLastStatus(SefazDistControl.Status.ERRO);
        control.setLastDetail("Certificado recusado");
        when(controlRepository.findByCompany_Id(company.getId())).thenReturn(Optional.of(control));

        assertThat(types()).isEmpty();

        plan(PlanServiceEnum.FISCAL_XML_IMPORT, PlanServiceEnum.SEFAZ_INTEGRATION);
        List<PendencyFinder.Finding> findings = finder.find(company);
        assertThat(findings).extracting(PendencyFinder.Finding::type).containsExactly(PendencyType.SEFAZ_CAPTURE_ERROR);
        assertThat(findings.getFirst().detail()).contains("Certificado recusado");
    }

    @Test
    void manifestationDeadlinesWithinFifteenDaysWhenThePlanManifests() {
        ManifestationDTOs.Note close = new ManifestationDTOs.Note("K1", UUID.randomUUID(), "10", "1", "FORNECEDOR", null,
                LocalDateTime.now().minusDays(170), BigDecimal.TEN, true, true, LocalDate.now().plusDays(10), 10);
        ManifestationDTOs.Note far = new ManifestationDTOs.Note("K2", null, "11", "1", "FORNECEDOR", null,
                LocalDateTime.now().minusDays(20), BigDecimal.TEN, false, false, LocalDate.now().plusDays(160), 160);
        when(manifestationService.notesOf(company)).thenReturn(List.of(close, far));

        plan(PlanServiceEnum.FISCAL_XML_IMPORT, PlanServiceEnum.SEFAZ_INTEGRATION);
        assertThat(types()).isEmpty();

        plan(PlanServiceEnum.FISCAL_XML_IMPORT, PlanServiceEnum.SEFAZ_INTEGRATION, PlanServiceEnum.SEFAZ_MANIFESTATION);
        List<PendencyFinder.Finding> findings = finder.find(company);
        assertThat(findings).extracting(PendencyFinder.Finding::naturalKey).containsExactly("MANIFESTATION_DEADLINE:K1");
        assertThat(findings.getFirst().objectType()).isEqualTo("NOTE");
    }

    @Test
    void aCompanyWithoutRegime() {
        company.setTaxRegimeType(null);

        assertThat(types()).containsExactly(PendencyType.REGIME_MISSING);
    }

    @Test
    void theSourcesAreOnlyComparedWithBothInThePlan() {
        plan(PlanServiceEnum.FISCAL_XML_IMPORT, PlanServiceEnum.SEFAZ_INTEGRATION);
        finder.find(company);

        verifyNoInteractions(summaryRepository);
        org.mockito.Mockito.verify(documentRepository, org.mockito.Mockito.never()).findOnlySieg(any(), any(), any(), any());
    }
}
