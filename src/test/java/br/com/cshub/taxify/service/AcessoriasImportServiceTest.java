package br.com.cshub.taxify.service;

import br.com.cshub.taxify.service.job.SyncProgress;
import br.com.cshub.taxify.service.job.IntegrationJobRunner;
import br.com.cshub.taxify.common.CurrentFirmService;
import br.com.cshub.taxify.enumerated.IntegrationType;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.document.Document;
import br.com.cshub.taxify.domain.user.User;
import br.com.cshub.taxify.domain.user_company.UserCompany;
import br.com.cshub.taxify.dto.acessorias.AcessoriasImportResultDTO;
import br.com.cshub.taxify.enumerated.DocumentTypeEnum;
import br.com.cshub.taxify.integration.acessorias.AcessoriasClient;
import br.com.cshub.taxify.integration.acessorias.AcessoriasCompanyDTO;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.repository.DocumentRepository;
import br.com.cshub.taxify.repository.UserCompanyRepository;
import br.com.cshub.taxify.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AcessoriasImportServiceTest {

    @Mock private AcessoriasClient acessoriasClient;
    @Mock private CompanyRepository companyRepository;
    @Mock private DocumentRepository documentRepository;
    @Mock private UserRepository userRepository;
    @Mock private UserCompanyRepository userCompanyRepository;
    @Mock private CurrentFirmService currentFirmService;
    @Mock private MessageService messageService;
    @Mock private FirmIntegrationService firmIntegrationService;
    @Mock private IntegrationJobRunner jobRunner;

    private AcessoriasImportService service;
    private AccountingFirm firm;

    @BeforeEach
    void setUp() {
        service = new AcessoriasImportService(acessoriasClient, companyRepository, documentRepository, userRepository,
                userCompanyRepository, currentFirmService, firmIntegrationService, jobRunner, messageService);
        firm = new AccountingFirm(UUID.randomUUID());
        lenient().when(firmIntegrationService.getApiKey(firm.getId(), IntegrationType.ACESSORIAS)).thenReturn("token");
        lenient().when(messageService.getMessage(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(companyRepository.save(any(Company.class))).thenAnswer(invocation -> {
            Company company = invocation.getArgument(0);
            if (company.getId() == null) {
                company.setId(UUID.randomUUID());
            }
            return company;
        });
        lenient().when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createsMissingCompanyWithRegistrationDataAndLinksExistingResponsible() {
        User fiscal = user("fiscal@escritorio.com");
        when(userRepository.findByAccountingFirm_IdAndDeletedIsFalse(firm.getId())).thenReturn(List.of(fiscal));
        when(acessoriasClient.listCompanies("token", null)).thenReturn(List.of(new AcessoriasCompanyDTO(
                "462", "38.217.129/0001-29", "ACME LTDA", "Acme", "Ativa", "3199999999", "mg",
                "2024-01-10", "0000-00-00", "Simples Nacional", "Geral",
                List.of(new AcessoriasCompanyDTO.StateRegistration("123", "MG")),
                List.of(department("Fiscal", "FISCAL@escritorio.com"), department("Contábil", "fiscal@escritorio.com"),
                        department("Pessoal", "ninguem@escritorio.com"), department("CS", "")))));

        AcessoriasImportResultDTO result = service.importFirm(firm, false, SyncProgress.NONE);

        assertThat(result.created()).isEqualTo(1);
        ArgumentCaptor<Company> companyCaptor = ArgumentCaptor.forClass(Company.class);
        verify(companyRepository).save(companyCaptor.capture());
        Company company = companyCaptor.getValue();
        assertThat(company.getName()).isEqualTo("ACME LTDA");
        assertThat(company.getTradeName()).isEqualTo("Acme");
        assertThat(company.getUf()).isEqualTo("MG");
        assertThat(company.getTaxRegime()).isEqualTo("Simples Nacional");
        assertThat(company.getStateRegistration()).isEqualTo("123/MG");
        assertThat(company.getActive()).isTrue();
        assertThat(company.getClientSince()).isEqualTo(LocalDate.of(2024, 1, 10));
        assertThat(company.getClientUntil()).isNull();
        assertThat(company.getAcessoriasId()).isEqualTo("462");
        assertThat(company.getAccountingFirm()).isSameAs(firm);
        assertThat(company.getDocument().getNumber()).isEqualTo("38217129000129");
        assertThat(company.getDocument().getType()).isEqualTo(DocumentTypeEnum.CNPJ);

        ArgumentCaptor<UserCompany> linkCaptor = ArgumentCaptor.forClass(UserCompany.class);
        verify(userCompanyRepository).save(linkCaptor.capture());
        assertThat(linkCaptor.getValue().getUser()).isSameAs(fiscal);
        assertThat(linkCaptor.getValue().getRole()).isEqualTo("Fiscal, Contábil");
        assertThat(result.linksCreated()).isEqualTo(1);
        assertThat(result.responsiblesNotFound()).containsExactly("Resp Pessoal <ninguem@escritorio.com>");
        verify(firmIntegrationService).markSynced(eq(firm.getId()), eq(IntegrationType.ACESSORIAS), any(LocalDateTime.class));
    }

    @Test
    void updatesExistingCompanyMatchedByDigitsAndKeepsExistingLink() {
        Company existing = company("38.217.129/0001-29", "Nome antigo");
        User fiscal = user("fiscal@escritorio.com");
        when(companyRepository.findByAccountingFirm_Id(firm.getId())).thenReturn(List.of(existing));
        when(userRepository.findByAccountingFirm_IdAndDeletedIsFalse(firm.getId())).thenReturn(List.of(fiscal));
        when(userCompanyRepository.existsById(any())).thenReturn(true);
        when(acessoriasClient.listCompanies("token", null)).thenReturn(List.of(company("38217129000129", "Inativa",
                List.of(department("Fiscal", "fiscal@escritorio.com")))));

        AcessoriasImportResultDTO result = service.importFirm(firm, false, SyncProgress.NONE);

        assertThat(result.updated()).isEqualTo(1);
        assertThat(existing.getName()).isEqualTo("ACME LTDA");
        assertThat(existing.getActive()).isFalse();
        assertThat(result.linksCreated()).isZero();
        verify(userCompanyRepository, never()).save(any());
        verify(documentRepository, never()).save(any());
    }

    @Test
    void secondRunWithSameDataCountsAsUnchanged() {
        Company existing = company("38217129000129", "x");
        when(companyRepository.findByAccountingFirm_Id(firm.getId())).thenReturn(List.of(existing));
        when(acessoriasClient.listCompanies("token", null)).thenReturn(List.of(company("38217129000129", "Ativa", List.of())));

        service.importFirm(firm, false, SyncProgress.NONE);
        AcessoriasImportResultDTO second = service.importFirm(firm, false, SyncProgress.NONE);

        assertThat(second.unchanged()).isEqualTo(1);
        assertThat(second.updated()).isZero();
    }

    @Test
    void skipsInactiveNewCompaniesDocumentsOfOtherFirmsDeletedCompaniesAndCaepf() {
        Company deleted = company("11111111000111", "Excluída");
        deleted.setDeleted(true);
        when(companyRepository.findByAccountingFirm_Id(firm.getId())).thenReturn(List.of(deleted));
        when(documentRepository.existsByNumberAndTypeNative(eq("22222222000122"), eq(DocumentTypeEnum.CNPJ))).thenReturn(true);
        when(acessoriasClient.listCompanies("token", null)).thenReturn(List.of(
                company("33333333000133", "Inativa", List.of()),
                company("22222222000122", "Ativa", List.of()),
                company("11111111000111", "Ativa", List.of()),
                company("123456789012", "Ativa", List.of())));

        AcessoriasImportResultDTO result = service.importFirm(firm, false, SyncProgress.NONE);

        assertThat(result.created()).isZero();
        assertThat(result.skipped()).hasSize(4);
        verify(companyRepository, never()).save(any());
    }

    @Test
    void incrementalRunAsksOnlyForCompaniesChangedSinceLastImport() {
        LocalDateTime lastSync = LocalDateTime.of(2026, 11, 19, 3, 0);
        when(firmIntegrationService.getLastSyncAt(firm.getId(), IntegrationType.ACESSORIAS)).thenReturn(lastSync);
        when(acessoriasClient.listCompanies("token", lastSync)).thenReturn(List.of());

        service.importFirm(firm, true, SyncProgress.NONE);

        verify(acessoriasClient).listCompanies("token", lastSync);
        verify(acessoriasClient, never()).listCompanies(eq("token"), isNull());
        ArgumentCaptor<LocalDateTime> syncedAt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(firmIntegrationService).markSynced(eq(firm.getId()), eq(IntegrationType.ACESSORIAS), syncedAt.capture());
        assertThat(syncedAt.getValue()).isAfter(lastSync);
    }

    private static AcessoriasCompanyDTO company(String identificador, String status, List<AcessoriasCompanyDTO.Department> departments) {
        return new AcessoriasCompanyDTO("1", identificador, "ACME LTDA", null, status, null, null,
                null, null, null, null, null, departments);
    }

    private Company company(String document, String name) {
        Company company = new Company(UUID.randomUUID());
        company.setName(name);
        company.setAccountingFirm(firm);
        company.setDocument(new Document(document, DocumentTypeEnum.CNPJ));
        return company;
    }

    private static AcessoriasCompanyDTO.Department department(String name, String email) {
        return new AcessoriasCompanyDTO.Department("1", name, "Resp " + name, email);
    }

    private User user(String email) {
        User user = new User(UUID.randomUUID());
        user.setEmail(email);
        user.setAccountingFirm(firm);
        return user;
    }
}
