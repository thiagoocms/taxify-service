package br.com.cshub.taxify.service;

import br.com.cshub.taxify.common.CurrentFirmService;
import br.com.cshub.taxify.common.MessageService;
import br.com.cshub.taxify.common.SecretCipher;
import br.com.cshub.taxify.domain.accountingfirm.AccountingFirm;
import br.com.cshub.taxify.domain.certificate.FirmCertificate;
import br.com.cshub.taxify.dto.integration.FirmCertificateDTO;
import br.com.cshub.taxify.exception.BadRequestException;
import br.com.cshub.taxify.integration.sefaz.A1Certificate;
import br.com.cshub.taxify.integration.sieg.FiscalXmlFileStorage;
import br.com.cshub.taxify.repository.FirmCertificateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static br.com.cshub.taxify.service.CompanyCertificateService.ENCRYPTED_FILE_SUFFIX;
import static br.com.cshub.taxify.service.CompanyCertificateService.ENCRYPTED_PASSWORD_PREFIX;
import static br.com.cshub.taxify.service.CompanyCertificateService.PLAIN_FILE_SUFFIX;
import static br.com.cshub.taxify.service.CompanyCertificateService.PLAIN_PASSWORD_PREFIX;

/**
 * The accountant's e-CPF of the current firm (Integrações): upload, status, removal - and opening it for the
 * e-Fisco sync. Only an e-CPF is taken: e-Fisco logs in the person (contador or sócio) linked to the companies.
 */
@Service
public class FirmCertificateService {

    private static final Logger log = LoggerFactory.getLogger(FirmCertificateService.class);

    private final FirmCertificateRepository repository;
    private final CurrentFirmService currentFirmService;
    private final FiscalXmlFileStorage storage;
    private final SecretCipher cipher;
    private final MessageService messageService;

    public FirmCertificateService(FirmCertificateRepository repository, CurrentFirmService currentFirmService,
                                  FiscalXmlFileStorage storage, SecretCipher cipher, MessageService messageService) {
        this.repository = repository;
        this.currentFirmService = currentFirmService;
        this.storage = storage;
        this.cipher = cipher;
        this.messageService = messageService;
    }

    @Transactional(readOnly = true)
    public FirmCertificateDTO statusForCurrentFirm() {
        return toDto(repository.findByAccountingFirm_IdAndDeletedIsFalse(currentFirmService.requireId()).orElse(null));
    }

    @Transactional
    public FirmCertificateDTO uploadForCurrentFirm(byte[] pfx, String password) {
        AccountingFirm firm = currentFirmService.require();
        if (pfx == null || pfx.length == 0) {
            throw new BadRequestException("error.certificate.empty", messageService.getMessage("error.certificate.empty"));
        }
        A1Certificate certificate;
        try {
            certificate = A1Certificate.open(pfx, password);
        } catch (GeneralSecurityException e) {
            throw new BadRequestException("error.certificate.invalid", messageService.getMessage("error.certificate.invalid"));
        }
        if (certificate.validUntil().isBefore(LocalDateTime.now())) {
            throw new BadRequestException("error.certificate.expired", messageService.getMessage("error.certificate.expired"));
        }
        if (certificate.cpf() == null) {
            throw new BadRequestException("error.certificate.not.ecpf", messageService.getMessage("error.certificate.not.ecpf"));
        }

        boolean encrypt = cipher.isConfigured();
        if (!encrypt) {
            log.warn("Storing the accountant's A1 of firm {} WITHOUT encryption - CERT_ENCRYPTION_KEY is not set", firm.getId());
        }
        String location;
        try {
            location = encrypt
                    ? storage.storeCertificate(firm.getId(), cipher.encrypt(pfx), ENCRYPTED_FILE_SUFFIX)
                    : storage.storeCertificate(firm.getId(), pfx, PLAIN_FILE_SUFFIX);
        } catch (IOException e) {
            throw new IllegalStateException("Could not store the certificate", e);
        }
        FirmCertificate entity = repository.findByAccountingFirm_IdAndDeletedIsFalse(firm.getId()).orElseGet(FirmCertificate::new);
        String previous = entity.getFilePath();
        entity.setAccountingFirm(firm);
        entity.setDeleted(false);
        entity.setFilePath(location);
        entity.setPasswordEncrypted(encrypt ? ENCRYPTED_PASSWORD_PREFIX + cipher.encryptText(password == null ? "" : password)
                : PLAIN_PASSWORD_PREFIX + (password == null ? "" : password));
        entity.setCpf(certificate.cpf());
        String subject = certificate.subjectName();
        entity.setSubjectName(subject.length() > 300 ? subject.substring(0, 300) : subject);
        entity.setValidFrom(certificate.validFrom());
        entity.setValidUntil(certificate.validUntil());
        repository.save(entity);
        deleteQuietly(previous);
        return toDto(entity);
    }

    @Transactional
    public FirmCertificateDTO deleteForCurrentFirm() {
        repository.findByAccountingFirm_IdAndDeletedIsFalse(currentFirmService.requireId()).ifPresent(entity -> {
            repository.delete(entity);
            deleteQuietly(entity.getFilePath());
        });
        return toDto(null);
    }

    @Transactional(readOnly = true)
    public Optional<FirmCertificate> find(UUID accountingFirmId) {
        return repository.findByAccountingFirm_IdAndDeletedIsFalse(accountingFirmId);
    }

    /** Reads the .pfx and its password, decrypting whichever was stored encrypted. */
    public A1Certificate open(FirmCertificate certificate) throws IOException, GeneralSecurityException {
        byte[] stored = storage.read(certificate.getFilePath());
        byte[] pfx = certificate.getFilePath().endsWith(ENCRYPTED_FILE_SUFFIX) ? cipher.decrypt(stored) : stored;
        String sealed = certificate.getPasswordEncrypted();
        String password = sealed.startsWith(PLAIN_PASSWORD_PREFIX) ? sealed.substring(PLAIN_PASSWORD_PREFIX.length())
                : cipher.decryptText(sealed.startsWith(ENCRYPTED_PASSWORD_PREFIX) ? sealed.substring(ENCRYPTED_PASSWORD_PREFIX.length()) : sealed);
        return A1Certificate.open(pfx, password);
    }

    @Transactional
    public void recordSync(UUID certificateId, String status, String detail) {
        repository.findById(certificateId).ifPresent(entity -> {
            entity.setLastSyncAt(LocalDateTime.now());
            entity.setLastSyncStatus(status);
            entity.setLastSyncDetail(detail == null ? null : detail.length() > 1000 ? detail.substring(0, 1000) : detail);
            repository.save(entity);
        });
    }

    static FirmCertificateDTO toDto(FirmCertificate certificate) {
        if (certificate == null) {
            return new FirmCertificateDTO(false, null, null, null, null, false, null, null, null);
        }
        return new FirmCertificateDTO(true, holderOf(certificate.getSubjectName()), maskCpf(certificate.getCpf()),
                certificate.getValidFrom(), certificate.getValidUntil(), certificate.getValidUntil().isBefore(LocalDateTime.now()),
                certificate.getLastSyncAt(), certificate.getLastSyncStatus(), certificate.getLastSyncDetail());
    }

    /** "CN=FULANO DE TAL:12345678901, OU=..." → "FULANO DE TAL". */
    static String holderOf(String subject) {
        if (subject == null) {
            return null;
        }
        for (String part : subject.split(",")) {
            String trimmed = part.strip();
            if (trimmed.startsWith("CN=")) {
                String cn = trimmed.substring(3);
                int colon = cn.lastIndexOf(':');
                return colon > 0 ? cn.substring(0, colon) : cn;
            }
        }
        return subject;
    }

    /** "123.***.***-01" - the screen never gets the whole CPF. */
    static String maskCpf(String cpf) {
        return cpf == null || cpf.length() != 11 ? cpf : cpf.substring(0, 3) + ".***.***-" + cpf.substring(9);
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
}
