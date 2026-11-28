package br.com.cshub.taxify.service;

import br.com.cshub.taxify.domain.company.Company;
import br.com.cshub.taxify.domain.company.StateRegistration;
import br.com.cshub.taxify.domain.fiscaldocument.GoodsInvoice;
import br.com.cshub.taxify.domain.fiscaldocument.IssuerDetails;
import br.com.cshub.taxify.domain.fiscaldocument.Party;
import br.com.cshub.taxify.domain.fiscaldocument.PartyAddress;
import br.com.cshub.taxify.dto.company.CompanyDTO;
import br.com.cshub.taxify.mapper.CompanyMapper;
import br.com.cshub.taxify.repository.CompanyRepository;
import br.com.cshub.taxify.repository.GoodsInvoiceRepository;
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
    private final GoodsInvoiceRepository documentRepository;
    private final CompanyMapper companyMapper;

    public CompanyRegistrationService(CompanyValidation companyValidation, CompanyRepository companyRepository,
                                      GoodsInvoiceRepository documentRepository, CompanyMapper companyMapper) {
        this.companyValidation = companyValidation;
        this.companyRepository = companyRepository;
        this.documentRepository = documentRepository;
        this.companyMapper = companyMapper;
    }

    @Transactional
    public CompanyDTO fillFromOwnNotes(UUID companyId) {
        Company company = companyValidation.checkExist(companyId);
        documentRepository.findLatestOwnIssue(companyId)
                .ifPresent(note -> {
                    if (fill(company, note)) {
                        companyRepository.save(company);
                    }
                });
        return companyMapper.toDto(company);
    }

    /** @return true when any field was filled */
    static boolean fill(Company company, GoodsInvoice note) {
        Party issuer = note.getIssuer();
        IssuerDetails details = note.getIssuerDetails();
        PartyAddress address = details.addressOrEmpty();
        boolean changed = false;
        changed |= fillIfEmpty(company::getAddressStreet, company::setAddressStreet, address.street());
        changed |= fillIfEmpty(company::getAddressNumber, company::setAddressNumber, address.number());
        changed |= fillIfEmpty(company::getAddressComplement, company::setAddressComplement, address.complement());
        changed |= fillIfEmpty(company::getAddressDistrict, company::setAddressDistrict, address.district());
        changed |= fillIfEmpty(company::getAddressCityCode, company::setAddressCityCode, digits(issuer.cityCode()));
        changed |= fillIfEmpty(company::getAddressCity, company::setAddressCity, address.cityName());
        changed |= fillIfEmpty(company::getAddressZipCode, company::setAddressZipCode, digits(address.zipCode()));
        changed |= fillIfEmpty(company::getUf, company::setUf, issuer.uf());
        changed |= fillIfEmpty(company::getPhone, company::setPhone, address.phone());
        changed |= fillIfEmpty(company::getMainCnae, company::setMainCnae, digits(details.cnae()));
        changed |= fillIfEmpty(company::getMunicipalRegistration, company::setMunicipalRegistration, details.municipalRegistration());

        // the note's IE is the one of the issuing UF - added when the company has none for that UF
        StateRegistration fromNote = StateRegistration.of(issuer.uf(), details.stateRegistration());
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
