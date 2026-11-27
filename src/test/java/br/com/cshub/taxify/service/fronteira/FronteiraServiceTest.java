package br.com.cshub.taxify.service.fronteira;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.fronteira.EfiscoExtract;
import br.com.cshub.taxify.domain.fronteira.EfiscoExtractNote;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlSummaryDTO.DivergenceSummary;
import br.com.cshub.taxify.dto.fronteira.FronteiraDTOs;
import br.com.cshub.taxify.dto.fronteira.FronteiraDTOs.Flag;
import br.com.cshub.taxify.enumerated.FiscalDirection;
import br.com.cshub.taxify.enumerated.FiscalDocumentStatus;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.repository.EfiscoExtractRepository;
import br.com.cshub.taxify.repository.FiscalXmlDocumentItemRepository;
import br.com.cshub.taxify.repository.FiscalXmlDocumentRepository;
import br.com.cshub.taxify.repository.SefazNoteSummaryRepository;
import br.com.cshub.taxify.domain.fronteira.FronteiraRule;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocumentItem;
import br.com.cshub.taxify.domain.xml.FiscalItemTaxDetail;
import br.com.cshub.taxify.dto.fronteira.FronteiraDTOs.Result;
import br.com.cshub.taxify.enumerated.FronteiraRuleKind;
import br.com.cshub.taxify.service.fiscalcheck.DocumentDivergenceService;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.AuditorAware;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FronteiraServiceTest {

    @Mock
    private EfiscoExtractParser parser;
    @Mock
    private EfiscoExtractRepository extractRepository;
    @Mock
    private FiscalXmlDocumentRepository documentRepository;
    @Mock
    private FiscalXmlDocumentItemRepository itemRepository;
    @Mock
    private DocumentDivergenceService divergenceService;
    @Mock
    private FronteiraRuleService ruleService;
    @Mock
    private SefazNoteSummaryRepository summaryRepository;
    @Mock
    private br.com.cshub.taxify.repository.ProductFiscalRuleRepository productRuleRepository;
    @Mock
    private CompanyValidation companyValidation;
    @Mock
    private MessageService messageService;
    @Mock
    private AuditorAware<String> auditorAware;

    private FronteiraService service;
    private Company company;
    private EfiscoExtract extract;

    @BeforeEach
    void setUp() {
        service = new FronteiraService(parser, extractRepository, documentRepository, itemRepository, divergenceService,
                ruleService, summaryRepository, productRuleRepository, companyValidation, messageService, auditorAware,
                org.mockito.Mockito.mock(br.com.cshub.taxify.integration.sieg.FiscalXmlFileStorage.class));
        company = new Company(UUID.randomUUID());
        company.setName("Confecções Exemplo");
        company.setUf("PE");
        when(companyValidation.checkExist(company.getId())).thenReturn(company);
        when(messageService.getMessage(anyString(), any(String[].class))).thenAnswer(call -> call.getArgument(0));
        when(messageService.getMessage(anyString())).thenAnswer(call -> call.getArgument(0));
        extract = new EfiscoExtract();
        extract.setCompany(company);
        extract.setPeriod("2026-11");
        extract.setImportedAt(LocalDateTime.now());
        when(extractRepository.findByCompany_IdAndPeriodAndDeletedIsFalse(company.getId(), "2026-11")).thenReturn(Optional.of(extract));
        when(extractRepository.findByCompany_IdAndDeletedIsFalseOrderByPeriodDesc(company.getId())).thenReturn(List.of(extract));
        when(divergenceService.summarize(eq(company.getId()), any())).thenReturn(Map.of());
    }

    private static EfiscoExtractNote note(int line, String cnpj, String numero, String valor, String icms) {
        EfiscoExtractNote note = new EfiscoExtractNote();
        note.setLineNumber(line);
        note.setIssuerCnpj(cnpj);
        note.setInvoiceNumber(numero);
        note.setUf("SP");
        note.setInvoiceAmount(new BigDecimal(valor));
        note.setIcmsDue(new BigDecimal(icms));
        return note;
    }

    private FiscalXmlDocument document(String cnpj, String numero, String valor) {
        FiscalXmlDocument document = new FiscalXmlDocument();
        document.setId(UUID.randomUUID());
        document.setCompany(company);
        document.setIssuerCnpj(cnpj);
        document.setNumero(numero);
        document.setValorTotalNota(new BigDecimal(valor));
        document.setDirection(FiscalDirection.INBOUND);
        document.setEmitenteUf("SP");
        document.setIssueDate(LocalDateTime.of(2026, 11, 5, 10, 0));
        return document;
    }

    @Test
    void theNotesOffTheUsualRateTheMissingXmlsAndTheCanceledOnes() {
        List<EfiscoExtractNote> notes = new ArrayList<>(List.of(
                note(1, "111", "10", "887.00", "48.78"),      // 5,50%
                note(2, "222", "20", "2400.00", "132.00"),    // 5,50%
                note(3, "222", "21", "13720.00", "754.60"),   // 5,50%
                note(4, "333", "30", "4261.61", "750.04"),    // 17,60% - off the rate
                note(5, "444", "40", "2.00", "0.11"),         // 5,50% after rounding a tiny note
                note(6, "555", "50", "100.00", "5.50")));     // no XML in Taxify
        extract.replaceNotes(notes);
        FiscalXmlDocument offRate = document("333", "30", "4261.61");
        FiscalXmlDocument canceled = document("222", "20", "2400.00");
        canceled.setStatus(FiscalDocumentStatus.CANCELED);
        FiscalXmlDocument otherValue = document("222", "21", "13000.00");
        when(documentRepository.findByCompany_IdAndIssuerCnpjInAndNumeroInAndDeletedIsFalse(eq(company.getId()), any(), any()))
                .thenReturn(List.of(document("111", "10", "887.00"), canceled, otherValue, offRate, document("444", "40", "2.00")));
        when(divergenceService.summarize(eq(company.getId()), any()))
                .thenReturn(Map.of(offRate.getId(), new DivergenceSummary(1, 0, List.of("CFOP diferente da regra"))));
        FiscalXmlDocument notCharged = document("999", "77", "500.00");
        FiscalXmlDocument local = document("888", "88", "300.00");
        local.setEmitenteUf("PE");
        when(documentRepository.findByCompany_IdAndIssueDateBetweenOrderByIssueDateDesc(eq(company.getId()), any(), any()))
                .thenReturn(List.of(notCharged, local, offRate));

        FronteiraDTOs.Report report = service.report(company.getId().toString(), "2026-11");

        assertThat(report.summary().taxaPredominante()).isEqualByComparingTo("5.5");
        assertThat(report.summary().icmsDevido()).isEqualByComparingTo("1691.03");
        assertThat(report.lines().get(3).flags()).containsExactly(Flag.FORA_DA_TAXA, Flag.DIVERGENCIA_FISCAL);
        assertThat(report.lines().get(3).diferencaParaTaxa()).isEqualByComparingTo("515.65"); // 750,04 - 4.261,61 x 5,5%
        assertThat(report.lines().get(3).nota().divergencias()).isNotNull();
        assertThat(report.lines().get(4).flags()).isEmpty();
        assertThat(report.lines().get(5).flags()).containsExactly(Flag.SEM_XML);
        assertThat(report.lines().get(1).flags()).containsExactly(Flag.NOTA_CANCELADA);
        assertThat(report.lines().get(2).flags()).containsExactly(Flag.VALOR_DIFERENTE);
        assertThat(report.summary().foraDaTaxa()).isEqualTo(1);
        assertThat(report.summary().diferencaForaDaTaxa()).isEqualByComparingTo("515.65");
        assertThat(report.summary().semXml()).isEqualTo(1);
        // the interstate entry nobody charged; the local one and the charged one are not listed
        assertThat(report.semCobranca()).extracting(FronteiraDTOs.Uncharged::documentId).containsExactly(notCharged.getId());
    }

    @Test
    void tooFewNotesToTellAUsualRate() {
        extract.replaceNotes(new ArrayList<>(List.of(note(1, "111", "10", "100.00", "5.50"), note(2, "333", "30", "100.00", "17.60"))));

        FronteiraDTOs.Report report = service.report(company.getId().toString(), "2026-11");

        assertThat(report.summary().taxaPredominante()).isNull();
        assertThat(report.lines()).allSatisfy(line -> assertThat(line.flags()).doesNotContain(Flag.FORA_DA_TAXA));
    }

    @Test
    void anExtractOfAnotherStateRegistrationIsRefused() {
        company.setStateRegistration("0999999-99/PE");
        when(parser.parse(any())).thenReturn(new EfiscoExtractParser.Extract(java.time.YearMonth.of(2026, 11), "0123456-78",
                null, null, null, null, null, null, null, null,
                List.of(new EfiscoExtractParser.Note(1, "r", null, "1", "SP", BigDecimal.TEN, BigDecimal.ONE, "111")), Map.of()));

        assertThatThrownBy(() -> service.importExtract(company.getId().toString(), "extrato.pdf", new byte[]{1}))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("error.fronteira.extract.other.ie");
    }

    @Test
    void onlyPernambucoCompanies() {
        company.setUf("PB");

        assertThatThrownBy(() -> service.importExtract(company.getId().toString(), "extrato.pdf", new byte[]{1}))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("error.fronteira.company.not.pe");
    }

    private FiscalXmlDocumentItem itemOf(FiscalXmlDocument document, String valor, String icms) {
        FiscalXmlDocumentItem item = new FiscalXmlDocumentItem();
        item.setId(UUID.randomUUID());
        item.setDocument(document);
        item.setNumeroItem(1);
        item.setNcm("61091000");
        item.setCfop("6102");
        item.setValorTotal(new BigDecimal(valor));
        item.setAliquotaIcms(new BigDecimal("12"));
        item.setValorIcms(new BigDecimal(icms));
        item.setTaxDetail(new FiscalItemTaxDetail(null, null, "0", null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null));
        return item;
    }

    /** Taxify's calculation against the charge: confere in the range, a mais, a menos, e-Fisco charging nothing. */
    @Test
    void theChargeAgainstTaxifysCalculation() {
        FronteiraRule interna = new FronteiraRule();
        interna.setId(UUID.randomUUID());
        interna.setKind(FronteiraRuleKind.ALIQUOTA_INTERNA);
        interna.setRate(new BigDecimal("20.5"));
        interna.setEnabled(true);
        interna.setReviewedAt(LocalDateTime.now());
        when(ruleService.rulesFor(company.getId())).thenReturn(List.of(interna));
        // each note from CE: R$ 1.000,00, ICMS 12% → Taxify: 1.000,00 × (20,5% − 12%) = 85,00
        extract.replaceNotes(new ArrayList<>(List.of(
                note(1, "111", "1", "1000.00", "85.02"),    // confere (rounding)
                note(2, "111", "2", "1000.00", "150.00"),   // a mais
                note(3, "111", "3", "1000.00", "50.00"),    // a menos
                note(4, "111", "4", "1000.00", "0.00"))));  // e-Fisco didn't calculate
        List<FiscalXmlDocument> documents = new ArrayList<>();
        List<FiscalXmlDocumentItem> items = new ArrayList<>();
        for (String numero : List.of("1", "2", "3", "4")) {
            FiscalXmlDocument document = document("111", numero, "1000.00");
            document.setEmitenteUf("CE");
            document.setValorProdutos(new BigDecimal("1000.00"));
            documents.add(document);
            items.add(itemOf(document, "1000.00", "120.00"));
        }
        when(documentRepository.findByCompany_IdAndIssuerCnpjInAndNumeroInAndDeletedIsFalse(eq(company.getId()), any(), any()))
                .thenReturn(documents);
        when(itemRepository.findByDocument_IdIn(any())).thenReturn(items);

        FronteiraDTOs.Report report = service.report(company.getId().toString(), "2026-11");

        assertThat(report.lines()).extracting(FronteiraDTOs.Line::resultado)
                .containsExactly(Result.CONFERE, Result.COBROU_A_MAIS, Result.COBROU_A_MENOS, Result.NAO_CALCULOU);
        assertThat(report.lines().get(1).diferenca()).isEqualByComparingTo("65.00");
        assertThat(report.summary().cobradoAMais()).isEqualByComparingTo("65.00");
        assertThat(report.summary().cobradoAMenos()).isEqualByComparingTo("120.00"); // 35,00 + 85,00
        assertThat(report.summary().icmsCalculado()).isEqualByComparingTo("340.00");
        assertThat(report.lines().get(0).calculo().itens().get(0).memoria()).isNotEmpty();
    }

    @Test
    void withoutAnExtractTheMonthsEntriesComeWithThePrevia() {
        when(extractRepository.findByCompany_IdAndPeriodAndDeletedIsFalse(company.getId(), "2026-11")).thenReturn(Optional.empty());
        FronteiraRule interna = new FronteiraRule();
        interna.setId(UUID.randomUUID());
        interna.setKind(FronteiraRuleKind.ALIQUOTA_INTERNA);
        interna.setRate(new BigDecimal("20.5"));
        interna.setEnabled(true);
        when(ruleService.rulesFor(company.getId())).thenReturn(List.of(interna));
        FiscalXmlDocument entry = document("111", "9", "1000.00");
        entry.setEmitenteUf("CE");
        entry.setValorProdutos(new BigDecimal("1000.00"));
        when(documentRepository.findByCompany_IdAndIssueDateBetweenOrderByIssueDateDesc(eq(company.getId()), any(), any()))
                .thenReturn(List.of(entry));
        when(itemRepository.findByDocument_IdIn(any())).thenReturn(List.of(itemOf(entry, "1000.00", "120.00")));

        FronteiraDTOs.Report report = service.report(company.getId().toString(), "2026-11");

        assertThat(report.extract()).isNull();
        assertThat(report.semCobranca()).hasSize(1);
        assertThat(report.previaIcms()).isEqualByComparingTo("85.00");
        assertThat(report.semCobranca().get(0).calculo().conferido()).isFalse(); // the modal rate is still "a conferir"
    }
}
