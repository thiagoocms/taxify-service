package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
import br.com.cshub.taxify.domain.xml.SefazNoteSummary;
import br.com.cshub.taxify.domain.xml.SefazNoteSummary.CienciaStatus;
import br.com.cshub.taxify.integration.fiscalxml.FiscalEventData;
import br.com.cshub.taxify.enumerated.PlanServiceEnum;
import br.com.cshub.taxify.integration.sefaz.A1Certificate;
import br.com.cshub.taxify.integration.sefaz.SefazDistClient;
import br.com.cshub.taxify.integration.sefaz.SefazDistResponse;
import br.com.cshub.taxify.integration.sefaz.SefazEventClient;
import br.com.cshub.taxify.integration.sefaz.SefazEventResponse;
import br.com.cshub.taxify.integration.sefaz.SefazProperties;
import br.com.cshub.taxify.repository.FiscalXmlDocumentRepository;
import br.com.cshub.taxify.repository.SefazNoteSummaryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SefazManifestServiceTest {

    private static final String CNPJ = "11222333000181";
    private static final String KEY_1 = "26241199888777000166550010000000011000000010";
    private static final String KEY_2 = "26241199888777000166550010000000021000000025";
    private static final String KEY_3 = "26241199888777000166550010000000031000000030";

    @Mock
    private SefazNoteSummaryRepository summaryRepository;
    @Mock
    private FiscalXmlDocumentRepository documentRepository;
    @Mock
    private FiscalXmlEventService eventService;
    @Mock
    private FiscalXmlIntegrationService integrationService;
    @Mock
    private SefazEventClient eventClient;
    @Mock
    private SefazDistClient distClient;
    @Mock
    private br.com.cshub.taxify.repository.SubscriptionRepository subscriptionRepository;

    private SefazProperties properties;
    private SefazManifestService service;
    private Company company;
    private final A1Certificate certificate = mock(A1Certificate.class);

    @BeforeEach
    void setUp() {
        properties = new SefazProperties();
        service = new SefazManifestService(summaryRepository, documentRepository, eventService, integrationService,
                eventClient, distClient, properties, subscriptionRepository);
        company = new Company(UUID.randomUUID());
        company.setUf("PE");
        planWith(PlanServiceEnum.FISCAL_XML_IMPORT, PlanServiceEnum.SEFAZ_INTEGRATION, PlanServiceEnum.SEFAZ_MANIFESTATION);
        when(summaryRepository.findAwaitingCiencia(any(), anyInt(), any())).thenReturn(List.of());
        when(summaryRepository.findAwaitingFullXml(any(), any(), any(), any())).thenReturn(List.of());
        when(summaryRepository.findByCompany_IdAndAccessKey(any(), anyString())).thenReturn(Optional.empty());
        when(documentRepository.findFirstByCompany_IdAndAccessKey(any(), anyString())).thenReturn(Optional.empty());
    }

    private void planWith(PlanServiceEnum... services) {
        br.com.cshub.taxify.domain.plan.Plan plan = new br.com.cshub.taxify.domain.plan.Plan(UUID.randomUUID());
        plan.setServices(java.util.Set.of(services));
        br.com.cshub.taxify.domain.subscription.Subscription subscription =
                new br.com.cshub.taxify.domain.subscription.Subscription(UUID.randomUUID());
        subscription.setPlan(plan);
        when(subscriptionRepository.findFirstByCompanyIdAndDeletedIsFalseAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateDesc(
                eq(company.getId()), any(), any())).thenReturn(Optional.of(subscription));
    }

    private static SefazDistResponse.Document resNFe(String key) {
        String xml = "<resNFe xmlns=\"http://www.portalfiscal.inf.br/nfe\"><chNFe>" + key + "</chNFe><CNPJ>99888777000166</CNPJ>"
                + "<xNome>FORNECEDOR</xNome><dhEmi>2026-11-20T09:30:00-03:00</dhEmi><tpNF>1</tpNF><vNF>10.00</vNF>"
                + "<cSitNFe>1</cSitNFe></resNFe>";
        return new SefazDistResponse.Document("7", "resNFe_v1.01.xsd", xml.getBytes(StandardCharsets.UTF_8));
    }

    private static SefazNoteSummary summary(String key) {
        SefazNoteSummary summary = new SefazNoteSummary();
        summary.setAccessKey(key);
        summary.setSituation("1");
        return summary;
    }

    @Test
    void aSummaryIsKeptWaitingForItsXml() {
        int created = service.recordSummaries(company, List.of(resNFe(KEY_1),
                new SefazDistResponse.Document("8", "procNFe_v4.00.xsd", "<x/>".getBytes())));

        ArgumentCaptor<SefazNoteSummary> saved = ArgumentCaptor.captor();
        verify(summaryRepository).save(saved.capture());
        assertThat(created).isEqualTo(1);
        assertThat(saved.getValue().getAccessKey()).isEqualTo(KEY_1);
        assertThat(saved.getValue().getIssuerName()).isEqualTo("FORNECEDOR");
        assertThat(saved.getValue().getNsu()).isEqualTo("7");
        assertThat(saved.getValue().getCienciaStatus()).isEqualTo(CienciaStatus.PENDENTE);
    }

    @Test
    void aSummaryOfANoteAlreadyStoredOnlyMarksThatSefazHasIt() {
        FiscalXmlDocument stored = new FiscalXmlDocument();
        when(documentRepository.findFirstByCompany_IdAndAccessKey(company.getId(), KEY_1)).thenReturn(Optional.of(stored));

        int created = service.recordSummaries(company, List.of(resNFe(KEY_1)));

        assertThat(created).isZero();
        assertThat(stored.getFoundInSefazAt()).isNotNull();
        verify(summaryRepository, never()).save(any());
    }

    @Test
    void nothingIsSentWhenAutoCienciaIsOff() {
        properties.setAutoCiencia(false);

        assertThat(service.manifestAndFetch(company, certificate, CNPJ, true)).isEqualTo(SefazManifestService.Outcome.NONE);
        verifyNoInteractions(eventClient, distClient);
    }

    @Test
    void nothingIsSentWhenThePlanHasNoManifestation() {
        planWith(PlanServiceEnum.FISCAL_XML_IMPORT, PlanServiceEnum.SEFAZ_INTEGRATION);
        when(summaryRepository.findAwaitingCiencia(any(), anyInt(), any())).thenReturn(List.of(summary(KEY_1)));

        assertThat(service.manifests(company.getId())).isFalse();
        assertThat(service.manifestAndFetch(company, certificate, CNPJ, true)).isEqualTo(SefazManifestService.Outcome.NONE);
        verifyNoInteractions(eventClient, distClient);
    }

    @Test
    void theManifestationNeedsTheSefazCaptureOnThePlan() {
        planWith(PlanServiceEnum.FISCAL_XML_IMPORT, PlanServiceEnum.SIEG_INTEGRATION, PlanServiceEnum.SEFAZ_MANIFESTATION);

        assertThat(service.manifests(company.getId())).isFalse();
    }

    @Test
    void registeredAndDuplicatedCienciasCountAsSentAndARefusalIsRetriedLater() throws Exception {
        SefazNoteSummary one = summary(KEY_1);
        SefazNoteSummary two = summary(KEY_2);
        SefazNoteSummary three = summary(KEY_3);
        when(summaryRepository.findAwaitingCiencia(eq(company.getId()), eq(3), any())).thenReturn(List.of(one, two, three));
        when(eventClient.sendCiencia(certificate, CNPJ, List.of(KEY_1, KEY_2, KEY_3))).thenReturn(new SefazEventResponse("128", "ok",
                List.of(new SefazEventResponse.Result(KEY_1, "135", "Registrado", "891", "2026-11-23T10:00:05-03:00"),
                        new SefazEventResponse.Result(KEY_2, "573", "Duplicidade de evento", null, null),
                        new SefazEventResponse.Result(KEY_3, "596", "Evento apresentado apos o prazo", null, null))));

        SefazManifestService.Outcome outcome = service.manifestAndFetch(company, certificate, CNPJ, true);

        assertThat(outcome.cienciasSent()).isEqualTo(2);
        assertThat(outcome.cienciaErrors()).isEqualTo(1);
        assertThat(one.getCienciaStatus()).isEqualTo(CienciaStatus.ENVIADA);
        assertThat(one.getCienciaProtocol()).isEqualTo("891");
        assertThat(two.getCienciaStatus()).isEqualTo(CienciaStatus.ENVIADA);
        assertThat(three.getCienciaStatus()).isEqualTo(CienciaStatus.ERRO);
        assertThat(three.getCienciaAttempts()).isEqualTo(1);
        assertThat(three.getCienciaDetail()).isEqualTo("596 Evento apresentado apos o prazo");

        ArgumentCaptor<FiscalEventData> event = ArgumentCaptor.captor();
        verify(eventService).record(eq(company), event.capture()); // only the one Taxify registered
        assertThat(event.getValue().type()).isEqualTo("210210");
        assertThat(event.getValue().accessKey()).isEqualTo(KEY_1);
        assertThat(event.getValue().registered()).isTrue();
    }

    @Test
    void aRefusedBatchMarksEveryNote() throws Exception {
        SefazNoteSummary one = summary(KEY_1);
        when(summaryRepository.findAwaitingCiencia(any(), anyInt(), any())).thenReturn(List.of(one));
        when(eventClient.sendCiencia(any(), anyString(), any())).thenReturn(new SefazEventResponse("297", "Assinatura difere", List.of()));

        SefazManifestService.Outcome outcome = service.manifestAndFetch(company, certificate, CNPJ, true);

        assertThat(outcome.cienciaErrors()).isEqualTo(1);
        assertThat(one.getCienciaStatus()).isEqualTo(CienciaStatus.ERRO);
        assertThat(one.getCienciaDetail()).isEqualTo("Lote recusado: 297 Assinatura difere");
    }

    @Test
    void theFullNoteAskedByKeyIsImported() throws Exception {
        SefazNoteSummary released = summary(KEY_1);
        released.setCienciaStatus(CienciaStatus.ENVIADA);
        released.setCienciaAt(LocalDateTime.now().minusHours(2));
        when(summaryRepository.findAwaitingFullXml(eq(company.getId()), any(), any(), any())).thenReturn(List.of(released));
        byte[] full = "<nfeProc/>".getBytes();
        when(distClient.fetchByKey(certificate, "PE", CNPJ, KEY_1)).thenReturn(new SefazDistResponse("138", "ok", null, null,
                List.of(new SefazDistResponse.Document("9", "procNFe_v4.00.xsd", full))));

        SefazManifestService.Outcome outcome = service.manifestAndFetch(company, certificate, CNPJ, true);

        assertThat(outcome.fetched()).isEqualTo(1);
        verify(integrationService).importFromSefaz(company.getId(), List.of(full));
        assertThat(released.getLastFetchAt()).isNotNull();
    }

    @Test
    void aConsumoIndevidoStopsTheKeyQueries() throws Exception {
        when(summaryRepository.findAwaitingFullXml(any(), any(), any(), any())).thenReturn(List.of(summary(KEY_1), summary(KEY_2)));
        when(distClient.fetchByKey(any(), any(), any(), any())).thenReturn(new SefazDistResponse("656", "Consumo indevido", null, null, List.of()));

        SefazManifestService.Outcome outcome = service.manifestAndFetch(company, certificate, CNPJ, true);

        assertThat(outcome.blocked()).isTrue();
        verify(distClient).fetchByKey(certificate, "PE", CNPJ, KEY_1);
        verify(distClient, never()).fetchByKey(certificate, "PE", CNPJ, KEY_2);
    }

    @Test
    void aNoteSefazWontServeByKeyIsGivenUp() throws Exception {
        SefazNoteSummary old = summary(KEY_1);
        old.setCienciaStatus(CienciaStatus.ENVIADA);
        when(summaryRepository.findAwaitingFullXml(any(), any(), any(), any())).thenReturn(List.of(old));
        when(distClient.fetchByKey(any(), any(), any(), any())).thenReturn(new SefazDistResponse("632",
                "Solicitacao fora de prazo", null, null, List.of()));

        service.manifestAndFetch(company, certificate, CNPJ, true);

        assertThat(old.getCienciaStatus()).isEqualTo(CienciaStatus.ERRO);
        assertThat(old.getCienciaAttempts()).isEqualTo(properties.getCienciaMaxAttempts());
        assertThat(old.getFetchDetail()).isEqualTo("632 Solicitacao fora de prazo");
    }

    @Test
    void noKeyQueryAfterTheDistributionsConsumoIndevido() throws Exception {
        when(summaryRepository.findAwaitingFullXml(any(), any(), any(), any())).thenReturn(List.of(summary(KEY_1)));

        service.manifestAndFetch(company, certificate, CNPJ, false);

        verifyNoInteractions(distClient);
    }
}
