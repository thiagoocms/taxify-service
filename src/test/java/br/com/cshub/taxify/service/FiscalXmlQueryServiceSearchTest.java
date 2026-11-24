package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.fiscalrule.ProductFiscalRule;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlSummaryDTO;
import br.com.cshub.taxify.dto.fiscalxml.XmlSearchFilterDTO;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlSummaryDTO.DivergenceSummary;
import br.com.cshub.taxify.enumerated.ReviewStatus;
import br.com.cshub.taxify.service.fiscalcheck.DocumentDivergenceService;
import org.springframework.data.domain.Page;
import java.util.Map;
import br.com.cshub.taxify.integration.sieg.FiscalXmlFileStorage;
import br.com.cshub.taxify.integration.sieg.FiscalXmlMetadataParser;
import br.com.cshub.taxify.repository.FiscalXmlDocumentItemRepository;
import br.com.cshub.taxify.repository.FiscalXmlDocumentRepository;
import br.com.cshub.taxify.repository.ProductFiscalRuleRepository;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;

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
    private FiscalXmlDocumentRepository documentRepository;
    @Mock
    private FiscalXmlDocumentItemRepository itemRepository;
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
    private ProductFiscalRuleRepository ruleRepository;
    @Mock
    private DocumentDivergenceService divergenceService;

    private static FiscalXmlDocument document() {
        FiscalXmlDocument document = new FiscalXmlDocument();
        document.setId(UUID.randomUUID());
        return document;
    }

    @Test
    void oneCfopForTheWholeNoteOrSeveralWhenSegmented() {
        FiscalXmlQueryService service = new FiscalXmlQueryService(documentRepository, itemRepository, new FiscalXmlMetadataParser(),
                companyValidation, tenantAccessService, messageService, eventService, fileStorage, ruleRepository, divergenceService);
        Company company = new Company(UUID.randomUUID());
        when(companyValidation.checkExist(company.getId())).thenReturn(company);
        FiscalXmlDocument single = document();
        FiscalXmlDocument segmented = document();
        FiscalXmlDocument withoutItems = document();
        when(documentRepository.search(eq(company.getId()), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(single, segmented, withoutItems)));
        when(itemRepository.countByDocumentIds(any())).thenReturn(List.of());
        when(itemRepository.findDistinctCfopsByDocumentIds(any())).thenReturn(List.of(
                new Object[]{single.getId(), "P1", "5102"},
                new Object[]{segmented.getId(), "P2", "5405"},
                new Object[]{segmented.getId(), null, " 5102 "}));

        List<FiscalXmlSummaryDTO> rows = service.search(
                new XmlSearchFilterDTO(company.getId().toString(), null, null, null, null, null, null, null)).getContent();

        assertThat(rows.get(0).cfops()).containsExactly("5102");
        assertThat(rows.get(1).cfops()).containsExactly("5102", "5405");
        assertThat(rows.get(2).cfops()).isEmpty();
    }

    @Test
    void aRectifiedNoteShowsTheCfopOfTheRuleItWasRectifiedWith() {
        FiscalXmlQueryService service = new FiscalXmlQueryService(documentRepository, itemRepository, new FiscalXmlMetadataParser(),
                companyValidation, tenantAccessService, messageService, eventService, fileStorage, ruleRepository, divergenceService);
        Company company = new Company(UUID.randomUUID());
        when(companyValidation.checkExist(company.getId())).thenReturn(company);
        FiscalXmlDocument rectified = document();
        rectified.setRectifiedFilePath("s3://bucket/retificado.xml");
        rectified.setLastRectificationCheckedAt(LocalDateTime.now());
        FiscalXmlDocument original = document();
        when(documentRepository.search(eq(company.getId()), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(rectified, original)));
        when(itemRepository.countByDocumentIds(any())).thenReturn(List.of());
        when(itemRepository.findDistinctCfopsByDocumentIds(any())).thenReturn(List.of(
                new Object[]{rectified.getId(), "P1", "5405"},
                new Object[]{rectified.getId(), "P2", "5405"},
                new Object[]{original.getId(), "P1", "5405"}));
        ProductFiscalRule rule = new ProductFiscalRule(UUID.randomUUID());
        rule.setCodigoProduto("P1");
        rule.setCfopCorreto("5102");
        ReflectionTestUtils.setField(rule, "lastModifiedDate", LocalDateTime.now().minusDays(1));
        when(ruleRepository.findByCompany_IdAndDeletedIsFalse(company.getId())).thenReturn(List.of(rule));

        List<FiscalXmlSummaryDTO> rows = service.search(
                new XmlSearchFilterDTO(company.getId().toString(), null, null, null, null, null, null, null)).getContent();

        assertThat(rows.get(0).cfops()).containsExactly("5102", "5405"); // Segmentado after the rectification
        assertThat(rows.get(1).cfops()).containsExactly("5405");          // not rectified: what was issued
    }

    @Test
    void eachRowCarriesWhatTheChecksFoundAndTheCfopFilterGoesToTheQuery() {
        FiscalXmlQueryService service = new FiscalXmlQueryService(documentRepository, itemRepository, new FiscalXmlMetadataParser(),
                companyValidation, tenantAccessService, messageService, eventService, fileStorage, ruleRepository, divergenceService);
        Company company = new Company(UUID.randomUUID());
        when(companyValidation.checkExist(company.getId())).thenReturn(company);
        FiscalXmlDocument divergent = document();
        FiscalXmlDocument clean = document();
        when(documentRepository.search(eq(company.getId()), any(), any(), any(), any(), any(), any(), any(), any(), any(), eq("5102"), any()))
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
        FiscalXmlQueryService service = new FiscalXmlQueryService(documentRepository, itemRepository, new FiscalXmlMetadataParser(),
                companyValidation, tenantAccessService, messageService, eventService, fileStorage, ruleRepository, divergenceService);
        Company company = new Company(UUID.randomUUID());
        when(companyValidation.checkExist(company.getId())).thenReturn(company);
        FiscalXmlDocument flagged = document();
        FiscalXmlDocument clean = document();
        FiscalXmlDocument marked = document();
        marked.review(ReviewStatus.DIVERGENT, "x", "nota", LocalDateTime.now());
        FiscalXmlDocument flaggedToo = document();
        when(documentRepository.search(eq(company.getId()), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
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
