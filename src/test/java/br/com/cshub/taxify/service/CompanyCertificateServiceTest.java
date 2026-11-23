package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.SecretCipher;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.certificate.CompanyCertificate;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.document.Document;
import br.com.cshub.taxify.enumerated.DocumentTypeEnum;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.integration.sefaz.A1Certificate;
import br.com.cshub.taxify.integration.sieg.FiscalXmlFileStorage;
import br.com.cshub.taxify.repository.CompanyCertificateRepository;
import br.com.cshub.taxify.repository.SefazDistControlRepository;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CompanyCertificateServiceTest {

    @Mock
    private CompanyCertificateRepository repository;
    @Mock
    private SefazDistControlRepository controlRepository;
    @Mock
    private CompanyValidation companyValidation;
    @Mock
    private FiscalXmlFileStorage storage;
    @Mock
    private MessageService messageService;

    private final SecretCipher cipher = new SecretCipher(Base64.getEncoder().encodeToString(new byte[32]));
    private CompanyCertificateService service;
    private final AccountingFirm firm = new AccountingFirm(UUID.randomUUID());

    private Company company(String cnpj) {
        Company company = new Company(UUID.randomUUID());
        company.setName("Empresa " + cnpj);
        company.setDocument(new Document(cnpj, DocumentTypeEnum.CNPJ));
        company.setAccountingFirm(firm);
        when(companyValidation.checkExist(company.getId())).thenReturn(company);
        when(repository.findByCompany_Id(company.getId())).thenReturn(Optional.empty());
        return company;
    }

    private static byte[] testPfx() throws IOException {
        try (InputStream in = CompanyCertificateServiceTest.class.getResourceAsStream("/certificates/a1-teste.pfx")) {
            return in.readAllBytes();
        }
    }

    @BeforeEach
    void setUp() {
        service = new CompanyCertificateService(repository, controlRepository, companyValidation, storage, cipher, messageService);
        when(messageService.getMessage(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(messageService.getMessage(anyString(), any(String[].class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(controlRepository.findByCompany_Id(any())).thenReturn(Optional.empty());
    }

    @Test
    void storesThePfxAndPasswordEncrypted() throws Exception {
        Company company = company("11222333000181");
        byte[] pfx = testPfx();
        when(storage.storeCertificate(eq(company.getId()), any())).thenReturn("s3://bucket/certificados/a.pfx.enc");

        service.upload(company.getId(), pfx, "teste123");

        ArgumentCaptor<byte[]> stored = ArgumentCaptor.forClass(byte[].class);
        verify(storage).storeCertificate(eq(company.getId()), stored.capture());
        assertThat(stored.getValue()).isNotEqualTo(pfx);
        assertThat(cipher.decrypt(stored.getValue())).isEqualTo(pfx);

        ArgumentCaptor<CompanyCertificate> saved = ArgumentCaptor.forClass(CompanyCertificate.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getPasswordEncrypted()).isNotEqualTo("teste123");
        assertThat(cipher.decryptText(saved.getValue().getPasswordEncrypted())).isEqualTo("teste123");
        assertThat(saved.getValue().getCnpj()).isEqualTo("11222333000181");
        assertThat(saved.getValue().getValidUntil()).isAfter(LocalDateTime.now());
    }

    @Test
    void branchMayUploadTheHeadOfficeCertificate() throws Exception {
        Company branch = company("11222333000262");
        when(storage.storeCertificate(eq(branch.getId()), any())).thenReturn("x");

        service.upload(branch.getId(), testPfx(), "teste123");

        verify(repository).save(any());
    }

    @Test
    void wrongPasswordIsRefusedAndNothingIsStored() throws Exception {
        Company company = company("11222333000181");

        assertThatThrownBy(() -> service.upload(company.getId(), testPfx(), "errada"))
                .isInstanceOf(BadRequestException.class).hasMessage("error.certificate.invalid");
        verify(storage, never()).storeCertificate(any(), any());
    }

    @Test
    void certificateOfAnotherCnpjRootIsRefused() throws Exception {
        Company company = company("99888777000100");

        assertThatThrownBy(() -> service.upload(company.getId(), testPfx(), "teste123"))
                .isInstanceOf(BadRequestException.class).hasMessage("error.certificate.other.company");
        verify(storage, never()).storeCertificate(any(), any());
    }

    @Test
    void refusedWhenTheEncryptionKeyIsMissing() throws Exception {
        service = new CompanyCertificateService(repository, controlRepository, companyValidation, storage,
                new SecretCipher(""), messageService);
        Company company = company("11222333000181");

        assertThatThrownBy(() -> service.upload(company.getId(), testPfx(), "teste123"))
                .hasMessage("error.certificate.encryption.not.configured");
    }

    @Test
    void branchWithoutCertificateUsesTheOneOfTheSameRoot() {
        Company headOffice = company("11222333000181");
        Company branch = company("11222333000262");
        Company other = company("99888777000100");
        CompanyCertificate headOfficeCertificate = certificate(headOffice, LocalDateTime.now().plusMonths(6));
        when(repository.findAllOfFirm(firm.getId()))
                .thenReturn(List.of(certificate(other, LocalDateTime.now().plusYears(2)), headOfficeCertificate));

        assertThat(service.resolve(branch)).contains(headOfficeCertificate);
    }

    @Test
    void openDecryptsWhatUploadStored() throws Exception {
        CompanyCertificate stored = new CompanyCertificate();
        stored.setFilePath("s3://bucket/certificados/a.pfx.enc");
        stored.setPasswordEncrypted(cipher.encryptText("teste123"));
        when(storage.read(stored.getFilePath())).thenReturn(cipher.encrypt(testPfx()));

        A1Certificate opened = service.open(stored);

        assertThat(opened.cnpj()).isEqualTo("11222333000181");
    }

    private static CompanyCertificate certificate(Company owner, LocalDateTime validUntil) {
        CompanyCertificate certificate = new CompanyCertificate();
        certificate.setCompany(owner);
        certificate.setValidUntil(validUntil);
        return certificate;
    }
}
