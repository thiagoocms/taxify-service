package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.DocumentNumbers;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.SecretCipher;
import br.com.cshub.taxify.domain.certificate.CompanyCertificate;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.xml.SefazDistControl;
import br.com.cshub.taxify.dto.company.CompanyCertificateDTO;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.integration.sefaz.A1Certificate;
import br.com.cshub.taxify.integration.sieg.FiscalXmlFileStorage;
import br.com.cshub.taxify.repository.CompanyCertificateRepository;
import br.com.cshub.taxify.repository.SefazDistControlRepository;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

/**
 * Upload, lookup and opening of the companies' A1 certificates. The .pfx goes to the XML
 * storage (S3) encrypted, the password to the database encrypted - both with
 * {@link SecretCipher}. Only certificates that open with the given password, are still valid
 * and belong to the company's CNPJ root are accepted.
 */
@Service
public class CompanyCertificateService {

    private static final Logger log = LoggerFactory.getLogger(CompanyCertificateService.class);

    private final CompanyCertificateRepository repository;
    private final SefazDistControlRepository controlRepository;
    private final CompanyValidation companyValidation;
    private final FiscalXmlFileStorage storage;
    private final SecretCipher cipher;
    private final MessageService messageService;

    public CompanyCertificateService(CompanyCertificateRepository repository, SefazDistControlRepository controlRepository,
                                     CompanyValidation companyValidation, FiscalXmlFileStorage storage,
                                     SecretCipher cipher, MessageService messageService) {
        this.repository = repository;
        this.controlRepository = controlRepository;
        this.companyValidation = companyValidation;
        this.storage = storage;
        this.cipher = cipher;
        this.messageService = messageService;
    }

    @Transactional(readOnly = true)
    public CompanyCertificateDTO status(UUID companyId) {
        Company company = companyValidation.checkExist(companyId);
        return toDto(company, resolve(company).orElse(null), controlRepository.findByCompany_Id(companyId).orElse(null));
    }

    @Transactional
    public CompanyCertificateDTO upload(UUID companyId, byte[] pfx, String password) {
        Company company = companyValidation.checkExist(companyId);
        if (!cipher.isConfigured()) {
            throw new BadRequestException(messageService.getMessage("error.certificate.encryption.not.configured"));
        }
        if (pfx == null || pfx.length == 0) {
            throw new BadRequestException(messageService.getMessage("error.certificate.empty"));
        }
        A1Certificate certificate;
        try {
            certificate = A1Certificate.open(pfx, password);
        } catch (GeneralSecurityException e) {
            throw new BadRequestException(messageService.getMessage("error.certificate.invalid"));
        }
        if (certificate.validUntil().isBefore(LocalDateTime.now())) {
            throw new BadRequestException(messageService.getMessage("error.certificate.expired"));
        }
        String companyCnpj = cnpjOf(company);
        if (certificate.cnpj() != null && companyCnpj != null && !certificate.cnpj().startsWith(companyCnpj.substring(0, 8))) {
            throw new BadRequestException(messageService.getMessage("error.certificate.other.company", certificate.cnpj()));
        }

        String location;
        try {
            location = storage.storeCertificate(company.getId(), cipher.encrypt(pfx));
        } catch (IOException e) {
            throw new IllegalStateException("Could not store the certificate", e);
        }
        CompanyCertificate entity = repository.findByCompany_Id(companyId).orElseGet(CompanyCertificate::new);
        String previous = entity.getFilePath();
        entity.setCompany(company);
        entity.setDeleted(false);
        entity.setFilePath(location);
        entity.setPasswordEncrypted(cipher.encryptText(password == null ? "" : password));
        entity.setCnpj(certificate.cnpj());
        entity.setSubjectName(truncate(certificate.subjectName(), 300));
        entity.setValidFrom(certificate.validFrom());
        entity.setValidUntil(certificate.validUntil());
        repository.save(entity);
        deleteQuietly(previous);
        return status(companyId);
    }

    @Transactional
    public CompanyCertificateDTO delete(UUID companyId) {
        companyValidation.checkExist(companyId);
        repository.findByCompany_Id(companyId).ifPresent(entity -> {
            repository.delete(entity);
            deleteQuietly(entity.getFilePath());
        });
        return status(companyId);
    }

    /**
     * The certificate that signs for this company: its own, else one of a company of the
     * same firm with the same CNPJ root (a branch using the head office one). Valid ones
     * first, then the one lasting longest.
     */
    @Transactional(readOnly = true)
    public Optional<CompanyCertificate> resolve(Company company) {
        Optional<CompanyCertificate> own = repository.findByCompany_Id(company.getId()).filter(c -> !c.isDeleted());
        if (own.isPresent() || company.getAccountingFirm() == null) {
            return own;
        }
        String cnpj = cnpjOf(company);
        if (cnpj == null) {
            return Optional.empty();
        }
        String root = cnpj.substring(0, 8);
        return repository.findAllOfFirm(company.getAccountingFirm().getId()).stream()
                .filter(c -> {
                    String other = cnpjOf(c.getCompany());
                    return other != null && other.startsWith(root);
                })
                .max(Comparator.comparing((CompanyCertificate c) -> c.getValidUntil().isAfter(LocalDateTime.now()))
                        .thenComparing(CompanyCertificate::getValidUntil));
    }

    /** Reads and decrypts the .pfx and its password. */
    public A1Certificate open(CompanyCertificate certificate) throws IOException, GeneralSecurityException {
        byte[] pfx = cipher.decrypt(storage.read(certificate.getFilePath()));
        return A1Certificate.open(pfx, cipher.decryptText(certificate.getPasswordEncrypted()));
    }

    /** Only-digits CNPJ of the company, or null when its document isn't a CNPJ. */
    static String cnpjOf(Company company) {
        if (company == null || company.getDocument() == null) {
            return null;
        }
        String digits = DocumentNumbers.onlyDigits(company.getDocument().getNumber());
        return digits != null && digits.length() == 14 ? digits : null;
    }

    private CompanyCertificateDTO toDto(Company company, CompanyCertificate certificate, SefazDistControl control) {
        CompanyCertificateDTO.SefazStatus sefaz = control == null ? null : new CompanyCertificateDTO.SefazStatus(
                control.getLastRunAt(), control.getLastStatus() == null ? null : control.getLastStatus().name(),
                control.getLastDetail(), control.getLastNewNotes(), control.getLastSummaries(), control.getNextQueryAt(),
                control.getUltNsu(), control.getMaxNsu());
        if (certificate == null) {
            return new CompanyCertificateDTO(false, false, null, null, null, null, null, null, false,
                    cipher.isConfigured(), sefaz);
        }
        Company owner = certificate.getCompany();
        return new CompanyCertificateDTO(true, !owner.getId().equals(company.getId()), owner.getId(), owner.getName(),
                certificate.getSubjectName(), certificate.getCnpj(), certificate.getValidFrom(), certificate.getValidUntil(),
                certificate.getValidUntil().isBefore(LocalDateTime.now()), cipher.isConfigured(), sefaz);
    }

    private void deleteQuietly(String location) {
        if (location == null) {
            return;
        }
        try {
            storage.delete(location);
        } catch (IOException e) {
            log.warn("Could not delete the old certificate file {}", location, e);
        }
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
