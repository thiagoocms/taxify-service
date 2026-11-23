package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.DocumentNumbers;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.SecretCipher;
import br.com.cshub.taxify.domain.certificate.CompanyCertificate;
import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.subscription.Subscription;
import br.com.cshub.taxify.enumerated.PlanServiceEnum;
import br.com.cshub.taxify.repository.SubscriptionRepository;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

/**
 * Upload, lookup and opening of the companies' A1 certificates. The .pfx goes to the XML
 * storage (S3) and the password to the database. With {@code CERT_ENCRYPTION_KEY} set, both are
 * encrypted by {@link SecretCipher}; without it they are stored as they are (the bucket's own
 * server-side encryption only). Each record says how it was stored - the file by its suffix,
 * the password by its prefix - so certificates stored without the key keep opening after a key
 * is set, and new ones are encrypted from then on. Only certificates that open with the given
 * password, are still valid and belong to the company's CNPJ root are accepted.
 */
@Service
public class CompanyCertificateService {

    private static final Logger log = LoggerFactory.getLogger(CompanyCertificateService.class);
    static final String ENCRYPTED_FILE_SUFFIX = ".pfx.enc";
    static final String PLAIN_FILE_SUFFIX = ".pfx";
    static final String ENCRYPTED_PASSWORD_PREFIX = "enc:";
    static final String PLAIN_PASSWORD_PREFIX = "plain:";

    private final CompanyCertificateRepository repository;
    private final SefazDistControlRepository controlRepository;
    private final CompanyValidation companyValidation;
    private final FiscalXmlFileStorage storage;
    private final SecretCipher cipher;
    private final MessageService messageService;
    private final SubscriptionRepository subscriptionRepository;

    public CompanyCertificateService(CompanyCertificateRepository repository, SefazDistControlRepository controlRepository,
                                     CompanyValidation companyValidation, FiscalXmlFileStorage storage,
                                     SecretCipher cipher, MessageService messageService,
                                     SubscriptionRepository subscriptionRepository) {
        this.repository = repository;
        this.controlRepository = controlRepository;
        this.companyValidation = companyValidation;
        this.storage = storage;
        this.cipher = cipher;
        this.messageService = messageService;
        this.subscriptionRepository = subscriptionRepository;
    }

    /** Whether today's plan of the company imports notes from SEFAZ (FISCAL_XML_IMPORT + SEFAZ_INTEGRATION). */
    @Transactional(readOnly = true)
    public boolean sefazInPlan(UUID companyId) {
        LocalDate today = LocalDate.now();
        return subscriptionRepository
                .findFirstByCompanyIdAndDeletedIsFalseAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateDesc(
                        companyId, today, today)
                .map(Subscription::getPlan)
                .map(plan -> PlanServiceEnum.importsFrom(plan.getServices(), PlanServiceEnum.SEFAZ_INTEGRATION))
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public CompanyCertificateDTO status(UUID companyId) {
        Company company = companyValidation.checkExist(companyId);
        return toDto(company, resolve(company).orElse(null), controlRepository.findByCompany_Id(companyId).orElse(null));
    }

    @Transactional
    public CompanyCertificateDTO upload(UUID companyId, byte[] pfx, String password) {
        Company company = companyValidation.checkExist(companyId);
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

        boolean encrypt = cipher.isConfigured();
        if (!encrypt) {
            log.warn("Storing the A1 certificate of company {} WITHOUT encryption - CERT_ENCRYPTION_KEY is not set", companyId);
        }
        String location;
        try {
            location = encrypt
                    ? storage.storeCertificate(company.getId(), cipher.encrypt(pfx), ENCRYPTED_FILE_SUFFIX)
                    : storage.storeCertificate(company.getId(), pfx, PLAIN_FILE_SUFFIX);
        } catch (IOException e) {
            throw new IllegalStateException("Could not store the certificate", e);
        }
        CompanyCertificate entity = repository.findByCompany_Id(companyId).orElseGet(CompanyCertificate::new);
        String previous = entity.getFilePath();
        entity.setCompany(company);
        entity.setDeleted(false);
        entity.setFilePath(location);
        entity.setPasswordEncrypted(sealPassword(password == null ? "" : password, encrypt));
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

    /** Reads the .pfx and its password, decrypting whichever was stored encrypted. */
    public A1Certificate open(CompanyCertificate certificate) throws IOException, GeneralSecurityException {
        byte[] stored = storage.read(certificate.getFilePath());
        byte[] pfx = certificate.getFilePath().endsWith(ENCRYPTED_FILE_SUFFIX) ? cipher.decrypt(stored) : stored;
        return A1Certificate.open(pfx, openPassword(certificate.getPasswordEncrypted()));
    }

    private String sealPassword(String password, boolean encrypt) {
        return encrypt ? ENCRYPTED_PASSWORD_PREFIX + cipher.encryptText(password) : PLAIN_PASSWORD_PREFIX + password;
    }

    private String openPassword(String stored) {
        if (stored.startsWith(PLAIN_PASSWORD_PREFIX)) {
            return stored.substring(PLAIN_PASSWORD_PREFIX.length());
        }
        // "enc:" - or no prefix at all: the first certificates were always encrypted
        return cipher.decryptText(stored.startsWith(ENCRYPTED_PASSWORD_PREFIX)
                ? stored.substring(ENCRYPTED_PASSWORD_PREFIX.length()) : stored);
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
                    cipher.isConfigured(), sefazInPlan(company.getId()), sefaz);
        }
        Company owner = certificate.getCompany();
        return new CompanyCertificateDTO(true, !owner.getId().equals(company.getId()), owner.getId(), owner.getName(),
                certificate.getSubjectName(), certificate.getCnpj(), certificate.getValidFrom(), certificate.getValidUntil(),
                certificate.getValidUntil().isBefore(LocalDateTime.now()), cipher.isConfigured(),
                sefazInPlan(company.getId()), sefaz);
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
