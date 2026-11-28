package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.fiscaldocument.FiscalDocument;
import br.com.cshub.taxify.domain.fiscaldocument.GoodsInvoice;
import br.com.cshub.taxify.domain.fiscaldocument.GoodsTotals;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlSummaryDTO.DivergenceSummary;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlTotalsDTO;
import br.com.cshub.taxify.dto.fiscalxml.XmlSearchFilterDTO;
import br.com.cshub.taxify.enumerated.FiscalDirection;
import br.com.cshub.taxify.enumerated.ReviewStatus;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.integration.sieg.FiscalXmlFileStorage;
import br.com.cshub.taxify.integration.sieg.FiscalXmlMetadataParser;
import br.com.cshub.taxify.repository.FiscalDocumentItemRepository;
import br.com.cshub.taxify.repository.FiscalDocumentRepository;
import br.com.cshub.taxify.repository.FiscalDocumentSearchFilter;
import br.com.cshub.taxify.repository.GoodsInvoiceRepository;
import br.com.cshub.taxify.service.fiscalcheck.DocumentDivergenceService;
import br.com.cshub.taxify.support.TestNotes;
import br.com.cshub.taxify.validation.CfopFilterValidation;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The cards of the notes screen (spec 2026-11-27, CF-RN-10 to 17): which query, with which filter, and the in-memory path. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FiscalXmlQueryServiceTotalsTest {

    private static final ZoneId RECIFE = ZoneId.of("America/Recife");

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

    private FiscalXmlQueryService service;
    private Company company;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-28T12:00:00Z"), RECIFE);
        service = new FiscalXmlQueryService(documentRepository, goodsInvoiceRepository, itemRepository, new FiscalXmlMetadataParser(),
                companyValidation, tenantAccessService, messageService, eventService, fileStorage, divergenceService,
                new CfopFilterValidation(messageService), clock);
        company = new Company(UUID.randomUUID());
        when(companyValidation.checkExist(company.getId())).thenReturn(company);
        when(messageService.getMessage(anyString(), any(String[].class))).thenAnswer(call -> call.getArgument(0));
    }

    private XmlSearchFilterDTO filter(FiscalDirection direction, String participant, String fonte, String cfop, Boolean divergencia,
                                      List<String> cfops) {
        return new XmlSearchFilterDTO(company.getId().toString(), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), null,
                direction, participant, null, null, null, fonte, cfop, divergencia, cfops);
    }

    /** CF-RN-10/11/17: the tab is dropped, every other filter goes to the one predicate; the items with the CFOPs too. */
    @Test
    void theCardsQueryTheListsPredicateWithoutTheTab() {
        when(goodsInvoiceRepository.totalsByDirectionFiltered(any())).thenReturn(List.<Object[]>of(
                new Object[]{FiscalDirection.INBOUND, 3L, new BigDecimal("2550.00"), new BigDecimal("300.00"), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 1L}));
        when(itemRepository.cfopItemTotalsByDirection(any())).thenReturn(List.<Object[]>of(
                new Object[]{FiscalDirection.INBOUND, 3L, new BigDecimal("2100.00"), new BigDecimal("252.00"), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO}));

        FiscalXmlTotalsDTO totals = service.totals(filter(FiscalDirection.INBOUND, "Alfa", XmlSearchFilterDTO.ONLY_SIEG, null, null,
                List.of("5.102", "5102")));

        ArgumentCaptor<FiscalDocumentSearchFilter> used = ArgumentCaptor.forClass(FiscalDocumentSearchFilter.class);
        verify(goodsInvoiceRepository).totalsByDirectionFiltered(used.capture());
        assertThat(used.getValue().direction()).isNull();
        assertThat(used.getValue().participant()).isEqualTo("%alfa%");
        assertThat(used.getValue().cfops().values()).containsExactly("5102");
        assertThat(used.getValue().onlySiegBefore()).isEqualTo(LocalDateTime.of(2026, 11, 26, 9, 0)); // Recife clock - 48h
        assertThat(used.getValue().issueDateTo()).isEqualTo(LocalDate.of(2026, 11, 30).atTime(java.time.LocalTime.MAX));
        verify(itemRepository).cfopItemTotalsByDirection(used.getValue());
        assertThat(totals.filtered()).isTrue();
        assertThat(totals.inbound().count()).isEqualTo(3);
        assertThat(totals.inbound().total()).isEqualByComparingTo("2550.00");
        assertThat(totals.inbound().canceledCount()).isEqualTo(1);
        assertThat(totals.outbound()).isEqualTo(FiscalXmlTotalsDTO.DirectionTotals.empty());
        assertThat(totals.cfopItems().inbound().itemsAmount()).isEqualByComparingTo("2100.00");
        assertThat(totals.cfopItems().outbound().itemCount()).isZero();
    }

    /** CF-RN-13/17: without any filter the cards are the month's, no item subtotal. */
    @Test
    void withoutFiltersTheCardsAreNotMarkedAndHaveNoItemLine() {
        when(goodsInvoiceRepository.totalsByDirectionFiltered(any())).thenReturn(List.of());

        FiscalXmlTotalsDTO totals = service.totals(filter(FiscalDirection.OUTBOUND, " ", null, null, null, List.of()));

        assertThat(totals.filtered()).isFalse();
        assertThat(totals.cfopItems()).isNull();
        verifyNoInteractions(itemRepository);
    }

    /** CF-RN-15: the same notes the list keeps (divergent or marked), summed in memory; the CT-e is not a card. */
    @Test
    void onlyDivergentSumsTheKeptGoodsNotesInMemory() {
        GoodsInvoice n3 = TestNotes.note(TestNotes.company(TestNotes.COMPANY_CNPJ)).total("300.00").totals(icms("36.00"))
                .direction(FiscalDirection.INBOUND).build();
        GoodsInvoice n5 = TestNotes.note(TestNotes.company(TestNotes.COMPANY_CNPJ)).total("1050.00").totals(icms("120.00"))
                .direction(FiscalDirection.INBOUND).build();
        n5.review(ReviewStatus.DIVERGENT, "x", "nota", LocalDateTime.of(2026, 11, 20, 10, 0));
        GoodsInvoice clean = TestNotes.note(TestNotes.company(TestNotes.COMPANY_CNPJ)).total("999.00").direction(FiscalDirection.INBOUND).build();
        GoodsInvoice canceled = TestNotes.note(TestNotes.company(TestNotes.COMPANY_CNPJ)).total("200.00").direction(FiscalDirection.OUTBOUND).build();
        canceled.cancel(null);
        when(documentRepository.search(any(), any())).thenReturn(new PageImpl<>(List.<FiscalDocument>of(n3, n5, clean, canceled)));
        DivergenceSummary found = new DivergenceSummary(1, 0, List.of("Confira"));
        when(divergenceService.summarize(eq(company.getId()), any())).thenReturn(Map.of(n3.getId(), found, canceled.getId(), found));
        when(itemRepository.cfopItemTotalsOfDocuments(any(), any())).thenReturn(List.<Object[]>of(
                new Object[]{FiscalDirection.INBOUND, 1L, new BigDecimal("1000.00"), new BigDecimal("120.00"), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO}));

        FiscalXmlTotalsDTO totals = service.totals(filter(null, null, null, null, true, List.of("5102")));

        assertThat(totals.inbound().count()).isEqualTo(2);
        assertThat(totals.inbound().total()).isEqualByComparingTo("1350.00");
        assertThat(totals.inbound().icms()).isEqualByComparingTo("156.00");
        assertThat(totals.outbound().count()).isZero();
        assertThat(totals.outbound().canceledCount()).isEqualTo(1);
        assertThat(totals.filtered()).isTrue();
        ArgumentCaptor<java.util.Collection<UUID>> ids = ArgumentCaptor.captor();
        verify(itemRepository).cfopItemTotalsOfDocuments(ids.capture(), eq(List.of("5102")));
        assertThat(ids.getValue()).containsExactlyInAnyOrder(n3.getId(), n5.getId()); // the canceled one stays out
        assertThat(totals.cfopItems().inbound().itemsAmount()).isEqualByComparingTo("1000.00");
    }

    private static GoodsTotals icms(String amount) {
        return new GoodsTotals(null, new BigDecimal(amount), null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null);
    }

    /** An origin that never existed: nothing matches, no query, the cards marked as filtered. */
    @Test
    void anOriginThatNeverExistedMatchesNothing() {
        FiscalXmlTotalsDTO totals = service.totals(new XmlSearchFilterDTO(company.getId().toString(), null, null, null, null, null,
                null, null, "nao existe!", null, null, null, null));

        assertThat(totals.inbound()).isEqualTo(FiscalXmlTotalsDTO.DirectionTotals.empty());
        assertThat(totals.filtered()).isTrue();
        verifyNoInteractions(goodsInvoiceRepository);
    }

    /** CA-30 on the cards. */
    @Test
    void aBrokenListIsRefusedBeforeAnyQuery() {
        assertThatThrownBy(() -> service.totals(filter(null, null, null, null, null, List.of("1102", "11"))))
                .isInstanceOfSatisfying(BadRequestException.class, error -> {
                    assertThat(error.getCode()).isEqualTo("error.cfopFilter.invalid");
                    assertThat(error.getExtras()).containsEntry("invalidValues", List.of("11"));
                });
        verifyNoInteractions(goodsInvoiceRepository, documentRepository);
    }
}
