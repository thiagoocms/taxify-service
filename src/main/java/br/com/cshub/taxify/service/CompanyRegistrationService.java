package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.company.StateRegistration;
import br.com.cshub.taxify.domain.xml.FiscalXmlDocument;
import br.com.cshub.taxify.dto.company.CompanyDTO;
import br.com.cshub.taxify.mapper.CompanyMapper;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.repository.FiscalXmlDocumentRepository;
import br.com.cshub.taxify.validation.CompanyValidation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Completes a company's registration from what it declares on its own notes: the issuer
 * block of its latest own-issued NF-e has address, CNAE, IE and IM. Only empty fields are
 * filled - what the firm typed is never overwritten.
 */
@Service
public class CompanyRegistrationService {

    private final CompanyValidation companyValidation;
    private final CompanyRepository companyRepository;
    private final FiscalXmlDocumentRepository documentRepository;
    private final CompanyMapper companyMapper;

    public CompanyRegistrationService(CompanyValidation companyValidation, CompanyRepository companyRepository,
                                      FiscalXmlDocumentRepository documentRepository, CompanyMapper companyMapper) {
        this.companyValidation = companyValidation;
        this.companyRepository = companyRepository;
        this.documentRepository = documentRepository;
        this.companyMapper = companyMapper;
    }

    @Transactional
    public CompanyDTO fillFromOwnNotes(UUID companyId) {
        Company company = companyValidation.checkExist(companyId);
        documentRepository.findFirstByCompany_IdAndOwnIssueTrueAndDeletedIsFalseOrderByIssueDateDesc(companyId)
                .ifPresent(note -> {
                    if (fill(company, note)) {
                        companyRepository.save(company);
                    }
                });
        return companyMapper.toDto(company);
    }

    /** @return true when any field was filled */
    static boolean fill(Company company, FiscalXmlDocument note) {
        boolean changed = false;
        changed |= fillIfEmpty(company::getAddressStreet, company::setAddressStreet, note.getEmitenteLogradouro());
        changed |= fillIfEmpty(company::getAddressNumber, company::setAddressNumber, note.getEmitenteEnderecoNumero());
        changed |= fillIfEmpty(company::getAddressComplement, company::setAddressComplement, note.getEmitenteComplemento());
        changed |= fillIfEmpty(company::getAddressDistrict, company::setAddressDistrict, note.getEmitenteBairro());
        changed |= fillIfEmpty(company::getAddressCityCode, company::setAddressCityCode, digits(note.getEmitenteCodigoMunicipio()));
        changed |= fillIfEmpty(company::getAddressCity, company::setAddressCity, note.getEmitenteMunicipio());
        changed |= fillIfEmpty(company::getAddressZipCode, company::setAddressZipCode, digits(note.getEmitenteCep()));
        changed |= fillIfEmpty(company::getUf, company::setUf, note.getEmitenteUf());
        changed |= fillIfEmpty(company::getPhone, company::setPhone, note.getEmitenteTelefone());
        changed |= fillIfEmpty(company::getMainCnae, company::setMainCnae, digits(note.getEmitenteCnae()));
        changed |= fillIfEmpty(company::getMunicipalRegistration, company::setMunicipalRegistration, note.getEmitenteInscricaoMunicipal());

        // the note's IE is the one of the issuing UF - added when the company has none for that UF
        StateRegistration fromNote = StateRegistration.of(note.getEmitenteUf(), note.getEmitenteInscricaoEstadual());
        if (fromNote != null) {
            List<StateRegistration> registrations = new ArrayList<>(company.getStateRegistrations());
            boolean hasUf = registrations.stream().anyMatch(r -> r.getUf().equals(fromNote.getUf()));
            boolean legacyText = registrations.isEmpty() && company.getStateRegistration() != null;
            if (!hasUf && !legacyText) {
                registrations.add(fromNote);
                company.replaceStateRegistrations(registrations);
                changed = true;
            }
        }
        return changed;
    }

    private static boolean fillIfEmpty(Supplier<String> current, Consumer<String> setter, String value) {
        String existing = current.get();
        if ((existing != null && !existing.isBlank()) || value == null || value.isBlank()) {
            return false;
        }
        setter.accept(value.strip());
        return true;
    }

    private static String digits(String value) {
        String digits = value == null ? "" : value.replaceAll("\\D", "");
        return digits.isEmpty() ? null : digits;
    }
}
