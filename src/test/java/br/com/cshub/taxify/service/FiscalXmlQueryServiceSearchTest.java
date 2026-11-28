package br.com.cshub.taxify.service;

import br.com.cshub.taxify.support.TestNotes;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.fiscaldocument.GoodsInvoice;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlSummaryDTO;
import br.com.cshub.taxify.dto.fiscalxml.XmlSearchFilterDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlSummaryDTO.DivergenceSummary;
import br.com.cshub.taxify.enumerated.ReviewStatus;
import br.com.cshub.taxify.service.fiscalcheck.DocumentDivergenceService;
import org.springframework.data.domain.Page;
import java.util.Map;
import br.com.cshub.taxify.integration.sieg.FiscalXmlFileStorage;
import br.com.cshub.taxify.integration.sieg.FiscalXmlMetadataParser;
import br.com.cshub.taxify.repository.FiscalDocumentItemRepository;
import br.com.cshub.taxify.repository.FiscalDocumentRepository;
import br.com.cshub.taxify.repository.GoodsInvoiceRepository;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** The list's CFOP column: distinct CFOPs of each note's items, read for the whole page at once. */
@ExtendWith(MockitoExtension.class)
class FiscalXmlQueryServiceSearchTest {

    @Mock
    private FiscalDocumentRepository documentRepository;
    @Mock
    private GoodsInvoiceRepository goodsInvoiceRepository;
    @Mock
    private FiscalDocumentItemRepository itemRepository;
    @Mock
    private CompanyValidation companyValidation;
    @Mock
    private TenantAccessService tenantAccessService;
    @Mock
    private MessageService messageService;
    @Mock
    private FiscalXmlEventService eventService;
    @Mock
    private FiscalXmlFileStorage fileStorage;
    @Mock
    private DocumentDivergenceService divergenceService;

    private static GoodsInvoice document() {
        return TestNotes.note(TestNotes.company(TestNotes.COMPANY_CNPJ)).build();
    }

    @Test
    void oneCfopForTheWholeNoteOrSeveralWhenSegmented() {
        FiscalXmlQueryService service = new FiscalXmlQueryService(documentRepository, goodsInvoiceRepository, itemRepository, new FiscalXmlMetadataParser(),
                companyValidation, tenantAccessService, messageService, eventService, fileStorage, divergenceService);
        Company company = new Company(UUID.randomUUID());
        when(companyValidation.checkExist(company.getId())).thenReturn(company);
        GoodsInvoice single = document();
        GoodsInvoice segmented = document();
        GoodsInvoice withoutItems = document();
        when(documentRepository.search(eq(company.getId()), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(single, segmented, withoutItems)));
        when(itemRepository.countByDocumentIds(any())).thenReturn(List.of());
        when(itemRepository.findDistinctEffectiveCfopsByDocumentIds(any())).thenReturn(List.of(
                new Object[]{single.getId(), "5102"},
                new Object[]{segmented.getId(), "5405"},
                new Object[]{segmented.getId(), " 5102 "}));

        List<FiscalXmlSummaryDTO> rows = service.search(
                new XmlSearchFilterDTO(company.getId().toString(), null, null, null, null, null, null, null)).getContent();

        assertThat(rows.get(0).cfops()).containsExactly("5102");
        assertThat(rows.get(1).cfops()).containsExactly("5102", "5405");
        assertThat(rows.get(2).cfops()).isEmpty();
    }

    /** FD-RN-57: the list shows the stored effective CFOP - the rectified file's -, whatever the rules say now. */
    @Test
    void aRectifiedNoteShowsTheCfopOfTheRuleItWasRectifiedWith() {
        FiscalXmlQueryService service = new FiscalXmlQueryService(documentRepository, goodsInvoiceRepository, itemRepository, new FiscalXmlMetadataParser(),
                companyValidation, tenantAccessService, messageService, eventService, fileStorage, divergenceService);
        Company company = new Company(UUID.randomUUID());
        when(companyValidation.checkExist(company.getId())).thenReturn(company);
        GoodsInvoice rectified = document();
        rectified.setRectifiedFilePath("s3://bucket/retificado.xml");
        rectified.setLastRectificationCheckedAt(LocalDateTime.now());
        GoodsInvoice original = document();
        when(documentRepository.search(eq(company.getId()), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(rectified, original)));
        when(itemRepository.countByDocumentIds(any())).thenReturn(List.of());
        when(itemRepository.findDistinctEffectiveCfopsByDocumentIds(any())).thenReturn(List.of(
                new Object[]{rectified.getId(), "5102"},
                new Object[]{rectified.getId(), "5405"},
                new Object[]{original.getId(), "5405"}));

        List<FiscalXmlSummaryDTO> rows = service.search(
                new XmlSearchFilterDTO(company.getId().toString(), null, null, null, null, null, null, null)).getContent();

        assertThat(rows.get(0).cfops()).containsExactly("5102", "5405"); // Segmentado after the rectification
        assertThat(rows.get(1).cfops()).containsExactly("5405");          // not rectified: what was issued
    }

    @Test
    void eachRowCarriesWhatTheChecksFoundAndTheCfopFilterGoesToTheQuery() {
        FiscalXmlQueryService service = new FiscalXmlQueryService(documentRepository, goodsInvoiceRepository, itemRepository, new FiscalXmlMetadataParser(),
                companyValidation, tenantAccessService, messageService, eventService, fileStorage, divergenceService);
        Company company = new Company(UUID.randomUUID());
        when(companyValidation.checkExist(company.getId())).thenReturn(company);
        GoodsInvoice divergent = document();
        GoodsInvoice clean = document();
        when(documentRepository.search(eq(company.getId()), any(), any(), any(), any(), any(), any(), any(), any(), any(), eq("5102"), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(divergent, clean)));
        DivergenceSummary summary = new DivergenceSummary(1, 0, List.of("CFOP diferente da regra"));
        when(divergenceService.summarize(eq(company.getId()), any())).thenReturn(Map.of(divergent.getId(), summary));

        List<FiscalXmlSummaryDTO> rows = service.search(new XmlSearchFilterDTO(company.getId().toString(), null, null, null,
                null, null, null, null, null, null, "5.102", null)).getContent();

        assertThat(rows.get(0).divergencias()).isEqualTo(summary);
        assertThat(rows.get(1).divergencias()).isNull();
    }

    @Test
    void onlyDivergentKeepsTheNotesTheChecksFlaggedOrMarkedDivergentAndPagesThem() {
        FiscalXmlQueryService service = new FiscalXmlQueryService(documentRepository, goodsInvoiceRepository, itemRepository, new FiscalXmlMetadataParser(),
                companyValidation, tenantAccessService, messageService, eventService, fileStorage, divergenceService);
        Company company = new Company(UUID.randomUUID());
        when(companyValidation.checkExist(company.getId())).thenReturn(company);
        GoodsInvoice flagged = document();
        GoodsInvoice clean = document();
        GoodsInvoice marked = document();
        marked.review(ReviewStatus.DIVERGENT, "x", "nota", LocalDateTime.now());
        GoodsInvoice flaggedToo = document();
        when(documentRepository.search(eq(company.getId()), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(flagged, clean, marked, flaggedToo)));
        DivergenceSummary summary = new DivergenceSummary(0, 1, List.of("Confira"));
        when(divergenceService.summarize(eq(company.getId()), any()))
                .thenReturn(Map.of(flagged.getId(), summary, flaggedToo.getId(), summary));

        Page<FiscalXmlSummaryDTO> page = service.search(new XmlSearchFilterDTO(company.getId().toString(), null, null, null,
                null, null, null, new br.com.cshub.taxify.dto.PageRequestDTO(0, 2, null, null), null, null, null, true));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(FiscalXmlSummaryDTO::id).containsExactly(flagged.getId(), marked.getId());
    }
}
