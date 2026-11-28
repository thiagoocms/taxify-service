package br.com.cshub.taxify.service.assessment;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.dto.assessment.IbsCbsSummaryDTO;
import br.com.cshub.taxify.dto.assessment.TaxAssessmentDTO;
import br.com.cshub.taxify.enumerated.FiscalDirection;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.repository.FiscalPeriodClosingRepository;
import br.com.cshub.taxify.repository.FiscalDocumentItemRepository;
import br.com.cshub.taxify.repository.GoodsInvoiceRepository;
import br.com.cshub.taxify.service.CompanyTaxRegimeService;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaxAssessmentServiceIbsCbsTest {

    private final CompanyValidation companyValidation = mock(CompanyValidation.class);
    private final FiscalDocumentItemRepository itemRepository = mock(FiscalDocumentItemRepository.class);
    private final TaxAssessmentService service = new TaxAssessmentService(companyValidation, mock(CompanyRepository.class),
            itemRepository, mock(GoodsInvoiceRepository.class), mock(FiscalPeriodClosingRepository.class),
            mock(TenantAccessService.class), mock(MessageService.class), mock(CompanyTaxRegimeService.class), mock(ObjectMapper.class),
            new TaxAssessmentCache());

    @Test
    void debitsFromSalesCreditsFromPurchasesAndTheBalance() {
        UUID id = UUID.randomUUID();
        Company company = new Company(id);
        company.setName("Empresa");
        when(companyValidation.checkExist(id)).thenReturn(company);
        when(itemRepository.sumIbsCbsByCompanyAndDirection(anyCollection(), eq(LocalDateTime.of(2026, 10, 1, 0, 0)),
                eq(LocalDateTime.of(2026, 11, 1, 0, 0)))).thenReturn(List.of(
                new Object[]{id, FiscalDirection.OUTBOUND, new BigDecimal("10.00"), new BigDecimal("90.00"), 12L},
                new Object[]{id, FiscalDirection.INBOUND, new BigDecimal("4.5"), new BigDecimal("40.5"), 3L}));

        TaxAssessmentDTO result = service.assess(id, "2026-10");

        IbsCbsSummaryDTO ibsCbs = result.ibsCbs();
        assertThat(ibsCbs.debitoIbs()).isEqualByComparingTo("10.00");
        assertThat(ibsCbs.debitoCbs()).isEqualByComparingTo("90.00");
        assertThat(ibsCbs.creditoIbs()).isEqualByComparingTo("4.50");
        assertThat(ibsCbs.creditoCbs()).isEqualByComparingTo("40.50");
        assertThat(ibsCbs.saldoIbs()).isEqualByComparingTo("5.50");
        assertThat(ibsCbs.saldoCbs()).isEqualByComparingTo("49.50");
        assertThat(ibsCbs.notasSaida()).isEqualTo(12);
        assertThat(ibsCbs.notasEntrada()).isEqualTo(3);
        assertThat(ibsCbs.observacao()).contains("2026");
    }

    @Test
    void noBlockWhenNoNoteHasTheGroup() {
        UUID id = UUID.randomUUID();
        Company company = new Company(id);
        when(companyValidation.checkExist(id)).thenReturn(company);
        when(itemRepository.sumIbsCbsByCompanyAndDirection(anyCollection(), any(), any())).thenReturn(List.of());

        assertThat(service.assess(id, "2026-10").ibsCbs()).isNull();
    }
}
