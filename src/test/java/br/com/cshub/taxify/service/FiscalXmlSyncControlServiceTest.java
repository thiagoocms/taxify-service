package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.TenantAccessService;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.xml.FiscalXmlSyncControl;
import br.com.cshub.taxify.dto.fiscalxml.FiscalXmlSyncControlDTO;
import br.com.cshub.taxify.repository.FiscalXmlSyncControlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FiscalXmlSyncControlServiceTest {

    @Mock
    private FiscalXmlSyncControlRepository repository;
    @Mock
    private TenantAccessService tenantAccessService;

    private FiscalXmlSyncControlService service;

    @BeforeEach
    void setUp() {
        lenient().when(tenantAccessService.getCurrentAccountingFirmId()).thenReturn(Optional.empty());
        service = new FiscalXmlSyncControlService(repository, tenantAccessService);
    }

    @Test
    void mapsCompanyTotalsAndComputesThePendingCount() {
        UUID companyId = UUID.randomUUID();
        Company company = new Company(companyId);
        company.setName("Empresa XPTO");

        FiscalXmlSyncControl control = new FiscalXmlSyncControl();
        control.setCompany(company);
        LocalDate inicio = LocalDate.of(2026, 9, 10);
        LocalDate fim = LocalDate.of(2026, 9, 11);
        control.setDataEmissaoInicio(inicio);
        control.setDataEmissaoFim(fim);
        control.setTotalSieg(200);
        control.setTotalImportado(100);
        LocalDateTime checkedAt = LocalDateTime.now();
        control.setUltimaVerificacaoEm(checkedAt);

        when(repository.findAll()).thenReturn(List.of(control));

        List<FiscalXmlSyncControlDTO> result = service.findAll();

        assertThat(result).hasSize(1);
        FiscalXmlSyncControlDTO dto = result.get(0);
        assertThat(dto.companyId()).isEqualTo(companyId.toString());
        assertThat(dto.companyName()).isEqualTo("Empresa XPTO");
        assertThat(dto.dataEmissaoInicio()).isEqualTo(inicio);
        assertThat(dto.dataEmissaoFim()).isEqualTo(fim);
        assertThat(dto.totalSieg()).isEqualTo(200);
        assertThat(dto.totalImportado()).isEqualTo(100);
        assertThat(dto.totalPendente()).isEqualTo(100);
        assertThat(dto.ultimaVerificacaoEm()).isEqualTo(checkedAt);
    }

    @Test
    void returnsEmptyListWhenThereIsNoControlDataYet() {
        when(repository.findAll()).thenReturn(List.of());

        assertThat(service.findAll()).isEmpty();
    }
}
